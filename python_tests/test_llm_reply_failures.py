"""A reply QP-CAT cannot use must be reported as a failure, not as "Done".

Two ways the Cluster Explainer used to say "Done. N clusters explained" over a table of
"(no suggestion)" rows: a reply with no JSON array in it (prose, or the wrong shape), and
a reply cut off at the output cap -- 2048 tokens, which a 20-cluster run exceeds, and
nothing read ``stop_reason``. Both now come back with an ``error_type`` and keep the raw
reply for the audit log.
"""

import importlib.util
import json
import sys
import types

import pytest

from conftest import SCRIPTS_DIR, requires


@pytest.fixture()
def mod():
    spec = importlib.util.spec_from_file_location(
        "run_llm_explainer", SCRIPTS_DIR / "run_llm_explainer.py"
    )
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


MARKERS = json.dumps(
    {
        "0": [{"name": "CD3", "score": 9.1, "logfoldchange": 2.0}],
        "1": [{"name": "PanCK", "score": 11.0, "logfoldchange": 3.1}],
    }
)

ONE_ROW = json.dumps(
    [
        {
            "cluster_id": 0,
            "phenotype": "T cell",
            "confidence": "high",
            "rationale": "CD3.",
            "supporting_markers": ["CD3"],
        }
    ]
)


def _paste(mod, provider, reply):
    return mod.run_explainer(
        provider, "x", "", "", MARKERS, 10, "all", 30, pasted_response=reply
    )


def test_a_pasted_reply_with_no_json_array_is_a_failure(mod):
    out = _paste(mod, "CHATBOT", "Cluster 0 looks like T cells and 1 like tumour.")
    assert out["error_type"] == "MALFORMED_RESPONSE"
    assert "no JSON array" in out["error_detail"]
    assert out["response_raw"].startswith("Cluster 0 looks like")


def test_an_array_naming_no_requested_cluster_is_a_failure(mod):
    reply = json.dumps([{"cluster_id": 7, "phenotype": "B cell"}])
    assert _paste(mod, "CHATBOT", reply)["error_type"] == "MALFORMED_RESPONSE"


def test_a_reply_answering_some_clusters_is_not_a_failure(mod):
    out = _paste(mod, "CHATBOT", ONE_ROW)
    assert "error_type" not in out
    rows = json.loads(out["explanations_json"])
    assert [r["phenotype"] for r in rows] == ["T cell", None]


def test_declining_every_cluster_in_the_right_shape_is_an_answer(mod):
    # phenotype null with a reason is the "insufficient signal" path, not a bad reply.
    reply = json.dumps(
        [
            {"cluster_id": 0, "phenotype": None, "rationale": "Too few markers."},
            {"cluster_id": 1, "phenotype": None, "rationale": "Too few markers."},
        ]
    )
    assert "error_type" not in _paste(mod, "CHATBOT", reply)


def test_mcp_keeps_its_own_refusal_for_a_prose_reply(mod):
    # McpExplainerTools refuses this case with text written for the agent.
    assert "error_type" not in _paste(mod, "MCP", "No JSON here.")


class _Block:
    def __init__(self, text):
        self.text = text


class _Usage:
    input_tokens = 900
    output_tokens = 16000


def _fake_anthropic(monkeypatch, stop_reason, text, calls):
    class _Messages:
        def create(self, **kwargs):
            calls.append(kwargs)
            return types.SimpleNamespace(
                content=[_Block(text)], usage=_Usage(), stop_reason=stop_reason
            )

    class _Client:
        def __init__(self, **kwargs):
            self.messages = _Messages()

    monkeypatch.setitem(
        sys.modules, "anthropic", types.SimpleNamespace(Anthropic=_Client)
    )


def _anthropic(mod):
    return mod.run_explainer(
        "ANTHROPIC", "claude-sonnet-5", "sk-ant-test", "", MARKERS, 10, "all", 30
    )


def test_a_reply_cut_off_at_the_output_cap_says_so(mod, monkeypatch):
    calls = []
    _fake_anthropic(monkeypatch, "max_tokens", '[{"cluster_id": 0, "phen', calls)
    out = _anthropic(mod)
    assert out["error_type"] == "RESPONSE_TRUNCATED"
    assert "max_tokens" in out["error_detail"]
    assert out["response_raw"] == '[{"cluster_id": 0, "phen'
    assert out["output_tokens"] == 16000


def test_the_output_cap_fits_a_large_run(mod, monkeypatch):
    # ~150 output tokens a cluster, plus thinking: 2048 did not fit 20 clusters.
    calls = []
    _fake_anthropic(monkeypatch, "end_turn", ONE_ROW, calls)
    out = _anthropic(mod)
    assert "error_type" not in out
    assert calls[0]["max_tokens"] == mod.ANTHROPIC_MAX_TOKENS >= 16000


def test_a_refusal_is_reported_not_parsed(mod, monkeypatch):
    _fake_anthropic(monkeypatch, "refusal", "", [])
    out = _anthropic(mod)
    assert out["error_type"] == "PROVIDER_REFUSED"


def test_an_ollama_reply_stopped_at_its_length_limit_says_so(mod, monkeypatch):
    class _Resp:
        status_code = 200
        text = ""

        def json(self):
            return {
                "response": '[{"cluster_id": 0',
                "done_reason": "length",
                "eval_count": 128,
                "prompt_eval_count": 700,
            }

    monkeypatch.setitem(
        sys.modules,
        "requests",
        types.SimpleNamespace(post=lambda *a, **k: _Resp()),
    )
    out = mod.run_explainer(
        "OLLAMA", "llama3", "", "http://localhost:11434", MARKERS, 10, "all", 30
    )
    assert out["error_type"] == "RESPONSE_TRUNCATED"
    assert out["response_raw"] == '[{"cluster_id": 0'


@requires("anthropic")
def test_the_sdk_still_reports_the_stop_reasons_we_read():
    import typing

    from anthropic.types import Message, StopReason

    assert "stop_reason" in Message.model_fields
    reasons = set(typing.get_args(StopReason))
    assert {"max_tokens", "refusal", "end_turn"} <= reasons
