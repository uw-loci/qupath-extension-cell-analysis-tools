"""
LLM Cluster Explainer -- Appose task script.

Sends per-cluster top-marker tables to a chosen LLM provider (Anthropic
or Ollama) and returns structured phenotype suggestions for each cluster.

Per the Phase 2 design contract (02_design.md, Carried-forward contracts):

  * Anthropic and Ollama are the only providers in v1. The provider
    abstraction (see `_call_provider`) accepts a future third provider
    without re-shaping the public API.
  * The error classifier maps provider-specific failures to a small enum
    the Java side can interpret.
  * `Authorization:` headers / bearer tokens are scrubbed from any error
    payload BEFORE the value crosses the Appose boundary back into Java.
    This is a tested invariant (see LlmKeyRedactionTest.java).
  * The prompt template id `cluster_phenotype_v1` is a constant in this
    module and is surfaced in the audit log via `prompt_template`.
  * The LLM is allowed to emit phenotype=null / confidence=null with a
    rationale explaining why ("insufficient signal" path).

Inputs (Appose 0.10.0+ injects these as bare variables, NOT task.inputs):
  provider             str   -- "ANTHROPIC" or "OLLAMA"
  model                str   -- provider-specific model id
  api_key              str   -- Anthropic key (empty string for Ollama)
  endpoint             str   -- Ollama base URL (empty string for Anthropic)
  marker_table_json    str   -- JSON dict {cluster_id: [{name, score, ...}]}
  top_n                int   -- max markers per cluster to include in the prompt
  cluster_ids          str   -- JSON list of cluster ids to explain (or "all")
  timeout_sec          int   -- per-HTTP-request timeout

Outputs (task.outputs):
  explanations_json    str   -- JSON list of {cluster_id, phenotype,
                                confidence, rationale, supporting_markers}
  prompt               str   -- full rendered prompt (for audit log)
  prompt_hash          str   -- sha256 of the prompt
  prompt_template      str   -- "cluster_phenotype_v1"
  response_raw         str   -- raw response text from the LLM
  token_count          int   -- total tokens (input + output) if reported, else -1
  input_tokens         int   -- input/prompt tokens if reported, else -1
  output_tokens        int   -- output/completion tokens if reported, else -1
  error_type           str   -- enum-shaped failure reason (only on failure)
  error_detail         str   -- short human-readable error (only on failure)
"""

import hashlib
import json
import logging
import re
import sys

logger = logging.getLogger("qpcat.llm")

PROMPT_TEMPLATE_ID = "cluster_phenotype_v1"


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def run_explainer(provider, model, api_key, endpoint, marker_table_json,
                  top_n, cluster_ids_spec, timeout_sec):
    """
    Drive a single LLM call and return a dict suitable for assignment to
    `task.outputs`. Callers should pass through the dict's keys verbatim.

    The function NEVER raises -- it returns a populated `error_type` /
    `error_detail` on failure instead, so the Java side can surface a
    deterministic banner. The only way this raises is a bug in the script
    itself (e.g., bad JSON literal in PROMPT_TEMPLATE), which is caught at
    test time, not user time.
    """
    out = {
        "explanations_json": "",
        "prompt": "",
        "prompt_hash": "",
        "prompt_template": PROMPT_TEMPLATE_ID,
        "response_raw": "",
        "token_count": -1,
        "input_tokens": -1,
        "output_tokens": -1,
    }

    try:
        marker_table = json.loads(marker_table_json) if marker_table_json else {}
    except Exception as e:
        out["error_type"] = "MALFORMED_RESPONSE"
        out["error_detail"] = "Failed to parse marker_table_json: " + _safe_str(e)
        return out

    cluster_ids = _resolve_cluster_ids(marker_table, cluster_ids_spec)
    if not cluster_ids:
        out["error_type"] = "INPUT_TOO_LARGE"
        out["error_detail"] = "No cluster ids selected for explanation."
        return out

    prompt = build_prompt(marker_table, cluster_ids, int(top_n))
    out["prompt"] = prompt
    out["prompt_hash"] = hashlib.sha256(prompt.encode("utf-8")).hexdigest()

    try:
        raw, input_tokens, output_tokens = _call_provider(
            provider, model, api_key, endpoint, prompt, int(timeout_sec))
    except _LlmError as e:
        out["error_type"] = e.error_type
        out["error_detail"] = scrub_secrets(e.detail)
        return out
    except Exception as e:  # noqa: BLE001 - last-resort guard
        out["error_type"] = "UNKNOWN"
        out["error_detail"] = scrub_secrets(_safe_str(e))
        return out

    out["response_raw"] = raw
    out["input_tokens"] = int(input_tokens) if input_tokens is not None else -1
    out["output_tokens"] = int(output_tokens) if output_tokens is not None else -1
    if out["input_tokens"] >= 0 and out["output_tokens"] >= 0:
        out["token_count"] = out["input_tokens"] + out["output_tokens"]
    else:
        out["token_count"] = -1

    explanations = parse_response(raw, cluster_ids)
    out["explanations_json"] = json.dumps(explanations)
    return out


# ---------------------------------------------------------------------------
# Prompt construction
# ---------------------------------------------------------------------------

PROMPT_TEMPLATE = """You are a computational pathology assistant. The user has \
just clustered single-cell multiplex imaging data and computed Wilcoxon \
rank-sum marker rankings per cluster. For each cluster, suggest the most \
likely phenotype (cell type / state) given the top markers and their \
Wilcoxon scores.

Output STRICTLY as a JSON array. Each element is an object with these keys:
  cluster_id          integer
  phenotype           string OR null (use null if signal is insufficient)
  confidence          one of "high", "medium", "low", OR null (same rule)
  rationale           short human-readable explanation (1-3 sentences)
  supporting_markers  list of marker names you relied on (subset of input)

If the signal is genuinely insufficient (mixed lineage markers, noise,
contradictory expression), set phenotype and confidence to null and
explain why in rationale -- DO NOT GUESS.

Return ONLY the JSON array. No markdown, no preamble, no trailing commentary.

Cluster data:
{cluster_blocks}
"""


def build_prompt(marker_table, cluster_ids, top_n):
    """Render the cluster_phenotype_v1 prompt for the given cluster ids."""
    blocks = []
    for cid in cluster_ids:
        markers = marker_table.get(str(cid), [])
        markers = list(markers)[: max(1, top_n)]
        if not markers:
            blocks.append("Cluster {0}: (no markers reported)".format(cid))
            continue
        lines = ["Cluster {0}:".format(cid)]
        for m in markers:
            name = m.get("name", "?")
            score = m.get("score", 0.0)
            log2fc = m.get("logfoldchange", 0.0)
            pval = m.get("pval_adj", 1.0)
            lines.append(
                "  - {0}  score={1:.2f}  log2fc={2:.2f}  pval_adj={3:.2e}".format(
                    name, float(score), float(log2fc), float(pval)))
        blocks.append("\n".join(lines))
    return PROMPT_TEMPLATE.format(cluster_blocks="\n\n".join(blocks))


def _resolve_cluster_ids(marker_table, spec):
    """Decode the cluster_ids_spec input into a sorted list of ints."""
    if spec is None or spec == "" or spec == "all":
        ids = []
        for k in marker_table.keys():
            try:
                ids.append(int(k))
            except (TypeError, ValueError):
                continue
        ids.sort()
        return ids
    try:
        parsed = json.loads(spec)
        if isinstance(parsed, list):
            return [int(x) for x in parsed]
    except Exception:
        pass
    # Fallback: comma-separated string
    out = []
    for tok in str(spec).split(","):
        tok = tok.strip()
        if not tok:
            continue
        try:
            out.append(int(tok))
        except ValueError:
            continue
    return out


# ---------------------------------------------------------------------------
# Provider abstraction
# ---------------------------------------------------------------------------

class _LlmError(Exception):
    """Internal exception carrying a classified error_type."""

    def __init__(self, error_type, detail):
        super().__init__(detail)
        self.error_type = error_type
        self.detail = detail


def _call_provider(provider, model, api_key, endpoint, prompt, timeout_sec):
    """
    Dispatch to the chosen provider. Returns
    ``(raw_response_text, input_tokens, output_tokens)``. Either token count
    may be ``-1`` if the provider did not report it. Phase 5 (pi-3) split the
    legacy single ``token_count`` into input/output components because
    Anthropic Sonnet output costs ~5x input -- summing them blocks any
    correct spend computation downstream.
    Raises ``_LlmError`` with a classified error_type on any provider-shaped
    failure. The provider-abstraction shape (this function's signature) is
    stable across v1 and accepts a future third provider as just another
    branch.
    """
    provider_norm = (provider or "").strip().upper()
    if provider_norm == "ANTHROPIC":
        return _call_anthropic(model, api_key, prompt, timeout_sec)
    if provider_norm == "OLLAMA":
        return _call_ollama(model, endpoint, prompt, timeout_sec)
    raise _LlmError(
        "UNKNOWN",
        "Unsupported provider: " + str(provider))


def _call_anthropic(model, api_key, prompt, timeout_sec):
    if not api_key:
        raise _LlmError("AUTH_INVALID",
                        "No Anthropic API key provided (in-memory key empty).")
    try:
        import anthropic  # type: ignore
    except ImportError:
        raise _LlmError(
            "UNKNOWN",
            "anthropic package not installed in the QP-CAT pixi env.")
    try:
        client = anthropic.Anthropic(api_key=api_key, timeout=float(timeout_sec))
        msg = client.messages.create(
            model=model,
            max_tokens=2048,
            messages=[{"role": "user", "content": prompt}],
        )
    except Exception as e:
        raise _classify_anthropic_error(e)

    text = _extract_anthropic_text(msg)
    inp_tokens, out_tokens = _extract_anthropic_tokens(msg)
    return text, inp_tokens, out_tokens


def _extract_anthropic_text(msg):
    try:
        parts = []
        for block in msg.content:
            t = getattr(block, "text", None)
            if t:
                parts.append(t)
        return "".join(parts) if parts else str(msg)
    except Exception:
        return _safe_str(msg)


def _extract_anthropic_tokens(msg):
    """Return ``(input_tokens, output_tokens)``; either may be ``-1``."""
    try:
        usage = getattr(msg, "usage", None)
        if usage is None:
            return -1, -1
        inp = getattr(usage, "input_tokens", None)
        out = getattr(usage, "output_tokens", None)
        inp_v = int(inp) if isinstance(inp, (int, float)) else -1
        out_v = int(out) if isinstance(out, (int, float)) else -1
        return inp_v, out_v
    except Exception:
        return -1, -1


def _classify_anthropic_error(e):
    """Translate an anthropic SDK exception into a classified _LlmError."""
    cls_name = type(e).__name__.lower()
    status = getattr(e, "status_code", None)
    msg = _safe_str(e)

    # Status-code first (most reliable)
    if status == 401 or status == 403 or "authentication" in cls_name:
        return _LlmError("AUTH_INVALID", "Anthropic auth rejected: " + msg)
    if status == 429 or "rate" in cls_name:
        return _LlmError("RATE_LIMIT", "Anthropic rate limit: " + msg)
    if status == 404 or "not_found" in cls_name:
        return _LlmError("MODEL_NOT_FOUND", "Anthropic model not found: " + msg)
    if status is not None and 500 <= int(status) < 600:
        return _LlmError("PROVIDER_DOWN", "Anthropic 5xx: " + msg)
    if "timeout" in cls_name or "timeout" in msg.lower():
        return _LlmError("TIMEOUT", "Anthropic request timed out: " + msg)
    if "connection" in cls_name or "apiconnection" in cls_name:
        return _LlmError("NETWORK_UNREACHABLE", "Anthropic network error: " + msg)
    return _LlmError("UNKNOWN", "Anthropic error: " + msg)


def _call_ollama(model, endpoint, prompt, timeout_sec):
    try:
        import requests  # type: ignore
    except ImportError:
        raise _LlmError(
            "UNKNOWN",
            "requests package not installed in the QP-CAT pixi env.")

    base = (endpoint or "http://localhost:11434").rstrip("/")
    url = base + "/api/generate"
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
    }
    try:
        resp = requests.post(url, json=payload, timeout=float(timeout_sec))
    except Exception as e:
        cls_name = type(e).__name__.lower()
        if "timeout" in cls_name:
            raise _LlmError("TIMEOUT",
                            "Ollama request timed out: " + _safe_str(e))
        raise _LlmError("NETWORK_UNREACHABLE",
                        "Cannot reach Ollama at " + base + ": " + _safe_str(e))

    if resp.status_code == 404:
        raise _LlmError("MODEL_NOT_FOUND",
                        "Ollama returned 404 -- model '" + str(model)
                        + "' not pulled?")
    if 500 <= resp.status_code < 600:
        raise _LlmError("PROVIDER_DOWN",
                        "Ollama 5xx: " + str(resp.status_code))
    if resp.status_code >= 400:
        raise _LlmError("UNKNOWN",
                        "Ollama HTTP " + str(resp.status_code) + ": "
                        + _safe_str(resp.text)[:200])

    try:
        data = resp.json()
    except Exception as e:
        raise _LlmError("MALFORMED_RESPONSE",
                        "Ollama returned non-JSON: " + _safe_str(e))

    text = data.get("response", "")
    if not isinstance(text, str):
        raise _LlmError("MALFORMED_RESPONSE",
                        "Ollama 'response' field is not a string.")
    eval_count = data.get("eval_count")
    prompt_eval_count = data.get("prompt_eval_count")
    out_tokens = int(eval_count) if isinstance(eval_count, int) else -1
    inp_tokens = int(prompt_eval_count) if isinstance(prompt_eval_count, int) else -1
    return text, inp_tokens, out_tokens


# ---------------------------------------------------------------------------
# Response parsing
# ---------------------------------------------------------------------------

def parse_response(raw, cluster_ids):
    """
    Parse the LLM's JSON-array response. Returns a list with one entry per
    requested cluster id; missing or malformed rows fall back to the
    "insufficient signal" representation so the UI always has a row.
    """
    parsed = _try_parse_json_array(raw)
    by_id = {}
    if isinstance(parsed, list):
        for entry in parsed:
            if not isinstance(entry, dict):
                continue
            cid = entry.get("cluster_id")
            try:
                cid = int(cid)
            except (TypeError, ValueError):
                continue
            by_id[cid] = entry

    out = []
    for cid in cluster_ids:
        entry = by_id.get(cid)
        if entry is None:
            out.append({
                "cluster_id": cid,
                "phenotype": None,
                "confidence": None,
                "rationale": "LLM did not return a row for this cluster.",
                "supporting_markers": [],
            })
            continue
        phenotype = entry.get("phenotype")
        if phenotype is not None and not isinstance(phenotype, str):
            phenotype = str(phenotype)
        confidence = entry.get("confidence")
        if confidence is not None and not isinstance(confidence, str):
            confidence = str(confidence)
        rationale = entry.get("rationale", "")
        if not isinstance(rationale, str):
            rationale = _safe_str(rationale)
        markers = entry.get("supporting_markers", []) or []
        if not isinstance(markers, list):
            markers = []
        markers = [str(m) for m in markers if m is not None]
        out.append({
            "cluster_id": cid,
            "phenotype": phenotype,
            "confidence": confidence,
            "rationale": rationale,
            "supporting_markers": markers,
        })
    return out


def _try_parse_json_array(raw):
    if not raw:
        return None
    # First, try the whole string
    try:
        return json.loads(raw)
    except Exception:
        pass
    # Fall back: extract the first balanced JSON array from a possibly
    # markdown-wrapped response.
    start = raw.find("[")
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(raw)):
        ch = raw[i]
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
            if depth == 0:
                snippet = raw[start:i + 1]
                try:
                    return json.loads(snippet)
                except Exception:
                    return None
    return None


# ---------------------------------------------------------------------------
# Secret scrubbing (tested invariant)
# ---------------------------------------------------------------------------

# Patterns we want to redact from ANY error payload before it crosses the
# Appose boundary. The Java OperationLogger writes whatever we hand it
# verbatim into the on-disk audit log, so this is the last line of defense.
_AUTH_HEADER_RE = re.compile(
    r"(?i)(authorization\s*:?\s*)(bearer\s+)?[A-Za-z0-9_\-./=+]+")
_ANTHROPIC_KEY_RE = re.compile(r"sk-ant-[A-Za-z0-9_\-]+")
_GENERIC_BEARER_RE = re.compile(
    r"(?i)\b(bearer\s+)[A-Za-z0-9_\-./=+]{8,}")
_X_API_KEY_RE = re.compile(
    r"(?i)(x-api-key\s*:?\s*)([A-Za-z0-9_\-./=+]+)")


def scrub_secrets(text):
    """
    Redact API-key-shaped substrings and Authorization headers from a string.
    Idempotent: re-running scrub_secrets on its own output is a no-op.

    This is the function the Java unit test LlmKeyRedactionTest exercises
    end-to-end (via a synthetic 401-with-key-in-payload) so any future
    weakening of the redaction surface is caught at build time.
    """
    if text is None:
        return ""
    s = str(text)
    s = _AUTH_HEADER_RE.sub(r"\1[REDACTED]", s)
    s = _X_API_KEY_RE.sub(r"\1[REDACTED]", s)
    s = _GENERIC_BEARER_RE.sub(r"\1[REDACTED]", s)
    s = _ANTHROPIC_KEY_RE.sub("[REDACTED]", s)
    return s


def _safe_str(obj):
    try:
        return str(obj)
    except Exception:
        return "<unprintable>"


# ---------------------------------------------------------------------------
# Appose task entry point
# ---------------------------------------------------------------------------
# When this module is loaded as an Appose task script (loaded fresh from
# JAR resources every run, per the QP-CAT pattern), Appose 0.10.0+ injects
# task inputs as bare variables. We collect them here, call run_explainer,
# and stuff the result into task.outputs.

try:
    _result = run_explainer(
        provider=provider,
        model=model,
        api_key=api_key,
        endpoint=endpoint,
        marker_table_json=marker_table_json,
        top_n=top_n,
        cluster_ids_spec=cluster_ids,
        timeout_sec=timeout_sec,
    )
    for _k, _v in _result.items():
        task.outputs[_k] = _v
    logger.info("LLM explainer done (template=%s, error=%s)",
                _result.get("prompt_template"),
                _result.get("error_type", "none"))
except NameError:
    # Module imported for testing -- no Appose task context. That's fine.
    logger.debug("run_llm_explainer.py imported without Appose task context.")
except Exception as _e:  # noqa: BLE001
    # Last-resort guard. If we get here, the script itself is broken --
    # surface it cleanly to the Java side rather than crashing the worker.
    try:
        task.outputs["error_type"] = "UNKNOWN"
        task.outputs["error_detail"] = scrub_secrets(
            "Internal explainer error: " + _safe_str(_e))
        task.outputs["prompt_template"] = PROMPT_TEMPLATE_ID
        task.outputs["explanations_json"] = ""
    except NameError:
        sys.stderr.write("LLM explainer internal error: " + _safe_str(_e) + "\n")
