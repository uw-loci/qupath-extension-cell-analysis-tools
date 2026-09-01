# LLM cluster explainer [Experimental]


Get a plain-English phenotype suggestion for each cluster, with rationale citing the top markers. Runs on the per-cluster Wilcoxon marker rankings that QP-CAT already produces -- no pixels are sent.

> **This feature has never been successfully run end-to-end by the QP-CAT developers.**
> "Experimental" here does not mean "works, but the output is unvalidated" -- it means the
> path has not been exercised against a live provider at all. Expect to hit problems no one
> has hit yet, and please [report them](troubleshooting.md#reporting-a-bug) if you do. Everything below
> describes the intended design, not observed behaviour.

The prompt template, output JSON shape, and audit-log row format may also change.

**Inspiration and prior art.** The design was inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s LLM phenotyping feature. QP-CAT's implementation differs in three notable ways: (a) supports Anthropic Claude and local Ollama in v1; OpenAI is intentionally not supported, (b) the LLM reads marker statistics only -- no pixels and no patient-identifying metadata cross the network boundary, (c) the full prompt and response are captured to a per-project audit log on every call, with API keys scrubbed on both the Java and Python sides before logging.

### When to use this feature

- **vs Rule-Based Phenotyping** -- rule-based gating is deterministic and publication-defensible; the LLM explainer is exploratory. Use the explainer to *propose* phenotype labels, then formalise them as gating rules for the final analysis
- **When the panel is unfamiliar** -- the most direct value. New panel + grad student = the explainer turns a 30-minute look-up-each-marker exercise into a 30-second sanity check
- **When writing up results** -- the audit log captures the full prompt and response, which can be cited verbatim in a methods section ("cluster labels were initially proposed by Claude Sonnet 4.5 (`claude-sonnet-4-5`) on $DATE using prompt template `cluster_phenotype_v1`; the full prompt and response are archived in the project log")

### Requirements

Choose one of:

- **Anthropic Claude** -- a current Anthropic API key from [console.anthropic.com](https://console.anthropic.com/). Pay-as-you-go (~$0.003-0.015 per cluster set in current Sonnet pricing; see *Cost expectations* below).
- **A running Ollama instance** -- [Ollama](https://ollama.com/) installed locally (or reachable on your network) with at least one chat model pulled. Recommended models: `llama3.1:8b` (4-5 GB, fast, decent), `qwen2.5:14b` (~9 GB, more accurate), or any other model you trust. No API costs; no data leaves your machine.

OpenAI is **not** supported in v1.

### Quick Start

1. Open an image (or project), run **Find cell populations (clustering)...** to completion
2. In the results dialog, open the **Cluster Explainer (LLM) [Experimental]** tab -- it is the **last** tab, after the data tabs
3. In the tab itself, select a provider, model, and (for Anthropic) paste your API key
4. Click **Run Explainer** -- wait 5-30 seconds depending on provider and model
5. Read the table: suggested phenotype, confidence, supporting markers, one-paragraph rationale per cluster

The results are also persisted to `SavedClusteringResult` so reopening past results shows the same table without re-paying the API call.

### Provider Setup

<details>
<summary><strong>Anthropic Claude (cloud, paid)</strong></summary>

1. Go to [console.anthropic.com](https://console.anthropic.com/), create an account if needed, and create a new API key
2. In the explainer tab, set **Provider** to "Anthropic"
3. Set **Model** to the default `claude-sonnet-4-5` (or pick `claude-opus-4-7` from the dropdown for a stronger model)
4. Paste your API key into the **API Key** field. The key is held in memory only for this QuPath session and is never written to disk
5. Click **Run Explainer**

**Environment variable shortcut:** set `QPCAT_ANTHROPIC_KEY=<your-key>` before launching QuPath. The explainer tab will show the key as masked text and you can leave the field alone. This is the recommended setup for shared workstations where you want the key to follow your user account rather than the QuPath GUI.

**Note:** the API key field is session-scoped -- there is no "remember this key" checkbox, and no keychain integration. Re-enter it, or set the environment variable.

</details>

<details>
<summary><strong>Ollama (local, free)</strong></summary>

1. Install [Ollama](https://ollama.com/) on your machine (or a machine reachable from this one)
2. Pull a chat model: `ollama pull llama3.1:8b` (or any other model)
3. Confirm Ollama is running: `curl http://localhost:11434/api/tags` should list your installed models
4. In the explainer tab, set **Provider** to "Ollama"
5. The **Endpoint** field is pre-populated with `http://localhost:11434` -- the standard Ollama default. Change it only if your Ollama server runs on a different host or port
6. Set **Model** to the exact tag you pulled (e.g. `llama3.1:8b`). The dropdown will be pre-populated with whatever the endpoint reports; you can also type a tag manually
7. Click **Run Explainer**

**Tips:**
- Quality varies widely by model. A 7-8B parameter model is usually good enough for a "what cell type is this cluster" question; a 1-3B model often hallucinates marker associations
- The first call after `ollama pull` may be slow while the model warms up; subsequent calls are typically 5-15 seconds
- Remote Ollama is fine: set the endpoint to `http://<host>:11434`. Be aware that traffic is unencrypted by default; restrict access at the network level

</details>

### What the LLM Sees

The prompt contains, per cluster:

- The cluster id (e.g. `Cluster 3`) and cell count
- The top-N (default 10) markers by Wilcoxon score, each with score, log fold change, and adjusted p-value
- The cluster-by-marker mean expression table for those markers (so the LLM can see "this cluster is high in CD8 and low in CD20" without needing to compute it)

The prompt **does not** contain:
- Pixel data of any kind
- Individual cell measurements
- Image metadata, file names, or paths
- Patient identifiers, sample names, or any project metadata
- Spatial coordinates of cells
- Anything from QuPath beyond the per-cluster summary statistics

You can see the exact prompt that was sent by opening the audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` and finding the `=== LLM EXPLAIN ===` entry. The prompt is reproduced verbatim in the indented `Prompt:` block. Both the Java and Python sides scrub `Authorization:` headers and `sk-ant-*` keys before any payload is logged, so the audit trail never contains the API key.

### Interpreting the Output

Each row of the result table has:

| Column | Meaning |
|---|---|
| **Cluster** | The cluster id (`Cluster 0`, `Cluster 1`, ...) |
| **Suggested phenotype** | The LLM's primary phenotype guess (e.g. "CD8+ cytotoxic T lymphocyte"). May show **(no suggestion)** -- see "Refused-to-guess rows" below |
| **Confidence** | One of `high`, `medium`, `low` -- the LLM's self-reported confidence. Treat with skepticism; `high` does not mean correct |
| **Supporting markers** | Top markers the LLM cited as evidence (e.g. "CD3, CD8, GZMB") |
| **Rationale** | One-paragraph explanation of why the LLM made this call |

**Refused-to-guess rows ("(no suggestion)").** The LLM is allowed to emit `phenotype: null` for a cluster when the marker signature is too weak or incoherent to support a guess. This is **expected behavior**, not an error -- the result table shows **(no suggestion)** in the Suggested phenotype column and the Rationale column still explains *why* the model refused (e.g. "insufficient signal: top markers are mutually inconsistent and adjusted p-values are above 0.5 across the board"). Treat these rows as a useful signal that the cluster itself may need a closer look, not as a failure of the explainer.

**The LLM may be wrong.** Common failure modes:
- **Marker name confusion** -- if your panel uses a non-standard naming convention (e.g. `MarkerCh01` or `tumor_marker_1`), the LLM has no way to know what the marker actually targets. Use real marker names in measurement columns whenever possible
- **Tissue-context blindness** -- the LLM doesn't know what tissue you're imaging. A "CD8+ T cell" suggestion is reasonable in tumor microenvironment but suspicious in tonsil cortex. Pass the tissue type as a future parameter if/when the UI exposes it
- **Plausible-but-wrong** -- an LLM will always produce *some* answer (when it doesn't refuse). A cluster with no biologically coherent marker signature may still get a confident label. Cross-check by inspecting the cluster's heatmap row and marker rankings yourself before trusting the suggestion
- **Hallucinated marker functions** -- the LLM may attribute behavior to a marker that does not match published literature. The audit log captures the full rationale so you can spot-check claims

**Cross-checking strategies:**
- Open the **Marker Rankings** tab and verify the supporting markers are actually top-ranked for that cluster
- For publication-tier analyses, treat LLM suggestions as **hypotheses to validate** with rule-based gating, not as final labels

### Reproducibility Caveats

LLM output is **not deterministic** unless the provider exposes a temperature=0 / seed control and the model has been pinned. By default:

- Anthropic Claude with `temperature=0` is approximately deterministic but not guaranteed byte-identical across requests
- Ollama with `temperature=0` and the same model snapshot is closer to deterministic, but the model can be re-pulled with a different hash at any time

The audit log captures the full prompt and response for every call. For a paper-grade trail:

1. Record the **exact provider and model string** from the audit log entry (e.g. `claude-sonnet-4-5`)
2. Record the **prompt template version** (e.g. `cluster_phenotype_v1`)
3. Archive the **`Response:` block** verbatim -- this is the actual text the LLM returned, including any cluster suggestions you accepted into your final analysis

The goal is **reproducibility of input** -- anyone reading your paper can run the same prompt against the same model and judge the answer for themselves. Reproducibility of *output* is not a property the LLM provides.

### Cost Expectations

One **Run Explainer** click is one LLM call with all clusters batched into the same prompt. Rough order-of-magnitude per click (Anthropic, current Sonnet pricing):

| Clusters | Top markers per cluster | Approx. input tokens | Approx. output tokens | Approx. cost |
|---|---|---|---|---|
| 5 | 10 | 1,500 | 800 | ~$0.005-0.01 |
| 10 | 10 | 3,000 | 1,500 | ~$0.01-0.02 |
| 20 | 10 | 6,000 | 3,000 | ~$0.02-0.05 |

Pricing changes; the audit log captures the exact `Input tokens:` and `Output tokens:` for every call so you can compute real spend at the right rate. (Anthropic charges different rates for input vs output -- Sonnet output is ~5x input -- so a combined number alone undercounts cost.) The combined `Token count:` is still emitted for backward compatibility. Ollama is free.

A **Cancel** button is exposed during the in-flight call. Cancelled calls are still logged (with a `Cancelled: true` field) but **may still consume tokens depending on provider and request stage; check your billing**. The cancel is a "soft" cancel on the Java side -- the Python HTTP request is allowed to complete in the background -- so an already-sent request may still be billed for input tokens even if you stop reading the response.

### [Experimental] notice

**Status: unproven.** No QP-CAT developer has completed a successful run of this feature.
It is shipped because the code path is complete and self-contained, not because it has been
demonstrated to work. Treat every statement below as design intent.

This is the first feature in QP-CAT that calls a remote LLM API. The surface area is
intentionally narrow:

- One prompt template (`cluster_phenotype_v1`); not user-editable yet
- Two providers (Anthropic, Ollama); OpenAI deferred
- One batched call per Run Explainer click; per-cluster async deferred
- API key is session-scoped; OS-keychain integration deferred

The audit-log entry, result-table JSON and prompt template are not stable surfaces. If you build tooling against them, expect to adjust.

Both the Java side (`LlmAuditScrubber`) and the Python side (`scrub_secrets` in `run_llm_explainer.py`) redact `Authorization:` headers and `sk-ant-*` keys before any payload reaches the audit log -- a tested invariant covered by `LlmKeyRedactionTest`.

---

## When it is worth using


The LLM cluster explainer ([HOW_TO_GUIDE section 10](llm-explainer.md)) sits alongside Rule-Based Phenotyping and clustering as a complementary tool. It operates on **per-cluster statistics** rather than per-cell data.

### When to Trust the LLM Output

- **As a starting hypothesis** -- use the suggested phenotype to seed your own thinking, then verify the supporting markers against the cluster's heatmap row and marker ranking before adopting the label
- **For exploration on unfamiliar panels** -- the LLM is most useful when you are not yet fluent in the marker vocabulary. It turns "what does CD163 mean again?" into a one-paragraph rationale
- **For sanity-checking student work** -- a PI reviewing a clustering run can scan the LLM suggestions next to the marker rankings and quickly flag clusters that warrant a closer look

### When to Cross-Check or Defer to Other Methods

- **For publication-tier phenotype labels** -- prefer rule-based gating. Use the LLM suggestion to design your gating strategy; cite the rules, not the LLM, in your methods section
- **When the suggestion is plausible but the markers don't support it** -- the LLM will produce confident answers for incoherent clusters (when it doesn't refuse outright). If the cluster's heatmap row is uniform or the top markers are biologically unrelated, ignore the suggestion and re-cluster instead
- **When the LLM emits "(no suggestion)"** -- this is a deliberate refusal, not an error. The rationale column explains why; treat it as a useful flag that the cluster itself probably warrants a closer look before trusting any label

### Prompt-Shape Limitations

The LLM sees **marker statistics, not individual cells**. This means:

- It cannot tell you why a single outlier cell ended up in a cluster
- It cannot reason about cell morphology (size, shape, texture) -- that information is not in the prompt
- It cannot account for tissue type unless you tell it (v1 does not surface this)
- It cannot validate the clustering itself -- it accepts the cluster boundaries QP-CAT gave it. If the clustering is bad, the LLM's confident labels for those bad clusters will also be bad

The LLM reasons over per-cluster marker statistics -- it cannot supply cell-level or
morphology-level reasoning.

### Audit-Log Discipline for Publications

Every LLM call is logged to `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the `=== LLM EXPLAIN ===` entry tag with provider, model, prompt-template version, prompt text, response text, and token counts. Both the Java side (`LlmAuditScrubber`) and the Python side (`scrub_secrets`) strip `Authorization:` headers and `sk-ant-*` keys from any payload before it reaches the log, so the audit trail is safe to share but does not contain the API key. For any paper that uses LLM-derived phenotype labels (even just as initial hypotheses), include in your methods section:

- **Provider and exact model string** (e.g. `claude-sonnet-4-5`, not just "Claude")
- **Prompt template version** as logged (currently `cluster_phenotype_v1`)
- **The fact that the call was made** -- LLM involvement, even at the exploratory stage, should be disclosed
- **Whether final phenotype labels were taken directly from the LLM output or re-derived from rule-based gating** -- these are very different reproducibility stories

Archive the audit log alongside your other reproducibility artifacts (clustering configs, rule sets, exported AnnData). The plain-text format is intentionally diff-friendly and version-control-friendly.

---

## Troubleshooting


> **This feature has never been successfully run end-to-end by the QP-CAT
> developers.** The error states below are derived from the code, not from
> observed failures, so the list is neither complete nor confirmed.

This page covers the error states you may see in the **Cluster Explainer (LLM)** tab of the cluster results dialog, what each one typically means, and what to do about it.

The Java side of QP-CAT shows a red status banner on every failure with a one-sentence summary. The full provider response, including any stack traces, is in the project audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the most recent `=== LLM EXPLAIN ===` entry. Both the Java (`LlmAuditScrubber`) and Python (`scrub_secrets`) sides strip `Authorization:` headers and `sk-ant-*` keys from any logged payload, so you can share an audit log without leaking your API key.

See also:

- [How-To Guide section 10](llm-explainer.md) -- the full workflow walkthrough
- [above](#when-it-is-worth-using) -- when to trust the output and how to cite it

---

<details>
<summary><strong>"No network connection" or "Could not reach Anthropic"</strong></summary>

**What you see:** The Run Explainer button completes quickly with a red status banner: *"Failed: cannot reach the provider."*

**What it usually means:** Your machine cannot reach `api.anthropic.com` over HTTPS. Most common causes: laptop is offline, corporate firewall/proxy is blocking the request, or DNS is misconfigured.

**What to do:**
1. Test connectivity in a terminal: `curl -I https://api.anthropic.com/v1/messages` should return an HTTP response (401 is fine -- it means the host is reachable; the auth just failed)
2. If you're behind a corporate proxy, ensure Java picks it up. Pass `-Dhttps.proxyHost=...` `-Dhttps.proxyPort=...` to QuPath at launch
3. As a fallback, use the Ollama provider for an offline-capable path

</details>

<details>
<summary><strong>"Invalid API key" or "401 Authentication failed"</strong></summary>

**What you see:** Red status banner: *"Failed: 401 -- API key was rejected."*

**What it usually means:** The key was mistyped, has been revoked, or has been rotated and the old value is still in the TextField or env var.

**What to do:**
1. Go to [console.anthropic.com](https://console.anthropic.com/) and verify the key is listed and active
2. Re-copy the key (watch for trailing whitespace) and paste again
3. If using the `QPCAT_ANTHROPIC_KEY` environment variable, restart QuPath so the new value is picked up -- the env var is read once at launch

</details>

<details>
<summary><strong>"Rate limit exceeded" or "429"</strong></summary>

**What you see:** Red status banner: *"Failed: 429 -- provider rate limit hit."* The Run button re-enables immediately; the provider's own `Retry-After` header is not currently surfaced in the UI.

**What it usually means:** You've made too many calls in too short a window, or your account is on a free/trial tier with low per-minute limits.

**What to do:**
1. Wait the suggested duration and click Run Explainer again
2. If this happens repeatedly, check your account's rate-limit tier in the Anthropic console
3. Ollama has no rate limits; switch providers if you're prototyping prompts at high volume

</details>

<details>
<summary><strong>"Malformed response" or "Could not parse LLM output"</strong></summary>

**What you see:** Red status banner: *"Failed: provider returned an unexpected response shape."* The full response is in the audit log; please file an issue.

**What it usually means:** The LLM did not produce JSON in the expected schema. Often happens with Ollama + a small/weak model, or after a provider-side change to default behavior.

**What to do:**
1. Open `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` and find the most recent `=== LLM EXPLAIN ===` entry. The `Response:` block contains the actual text the LLM returned
2. If the response looks plausible but isn't valid JSON, try a stronger model (Anthropic Sonnet, or Ollama with a larger model)
3. If the response is gibberish, the model may not be capable of this task -- switch models
4. File an issue with the audit-log entry attached so the prompt template can be hardened

**Not the same as "(no suggestion)":** A row showing **(no suggestion)** in the result table is *not* a malformed response. The LLM is allowed to emit `phenotype: null` with a rationale ("insufficient signal") when a cluster's marker signature is too weak or incoherent to support a guess. That is expected behavior, not an error -- see HOW_TO_GUIDE section 10, "Interpreting the Output".

</details>

<details>
<summary><strong>"Could not reach Ollama at &lt;endpoint&gt;"</strong></summary>

**What you see:** Red status banner: *"Failed: cannot reach the provider."* (This is the same banner the Anthropic-network-down case uses; the audit-log entry's `Endpoint:` row and `Error detail:` line will name Ollama and the URL it could not reach.)

**What it usually means:** The Ollama server is not running, has crashed, or is listening on a different host/port.

**What to do:**
1. In a terminal: `curl http://localhost:11434/api/tags` -- this should return JSON listing your installed models
2. If you get a connection-refused error, start Ollama: `ollama serve` (or use the Ollama app's tray icon)
3. If you're pointing at a remote Ollama, verify the host is reachable: `curl http://<host>:11434/api/tags` from this machine
4. Update the **Endpoint** field in the explainer tab and click Run again

</details>

<details>
<summary><strong>"Ollama model not found" or "model '&lt;tag&gt;' not pulled"</strong></summary>

**What you see:** Red status banner: *"Failed: model not found on provider."* The audit-log `Error detail:` line names the missing tag and suggests `ollama pull <tag>`.

**What it usually means:** The model tag you selected (or typed) is not present on the Ollama server.

**What to do:**
1. In a terminal on the Ollama host: `ollama list` -- shows installed models
2. Pull the missing model: `ollama pull llama3.1:8b` (or the tag you want)
3. Wait for the pull to complete -- this can be slow for large models (8B ~ 5 GB, 70B ~ 40 GB)
4. Click Run Explainer again

</details>

<details>
<summary><strong>"Request cancelled" / Cancel button was clicked</strong></summary>

**What you see:** Status banner: *"Cancelled. No results applied."*

**What it usually means:** You clicked Cancel while the call was in flight, or QuPath was closed before the call returned.

**What to do:**
- Nothing -- this is the expected behavior. The audit log notes the cancelled call (look for `Cancelled: true`). The cancel is a "soft" cancel on the Java side -- the underlying HTTP request is allowed to complete in the background -- so cancelled calls **may still consume tokens depending on provider and request stage; check your billing**. Anthropic, in particular, may bill for input tokens on a request that was already in flight.
- Click Run Explainer again when ready.

</details>

<details>
<summary><strong>The tab is greyed out / Run Explainer is disabled</strong></summary>

**What you see:** The Cluster Explainer (LLM) [Experimental] tab opens but the **Run Explainer** button is disabled with a tooltip like *"Marker rankings not available for this clustering result"* or *"Select a provider"*.

**What it usually means:** Either the clustering result does not contain Wilcoxon marker rankings (some algorithms or partial runs skip this), or you have not selected a provider in the tab's Provider dropdown.

**What to do:**
1. Re-run clustering with marker rankings enabled (default for all standard algorithms)
2. In the explainer tab itself, pick a provider from the **Provider** dropdown (Anthropic or Ollama). The provider config lives in the tab -- there is no global "set this once in Preferences" step to do separately

</details>

<details>
<summary><strong>The Python environment rebuilds on first upgrade</strong></summary>

**What you see:** The first time you launch QuPath after upgrading to a QP-CAT version that includes the LLM explainer, the QP-CAT Python environment performs a one-time additive rebuild as Appose detects the new pixi.toml. The clustering UI will not be available until the rebuild completes.

**What it usually means:** The Appose Python env's `pixi.toml` gained two new pure-Python dependencies (`anthropic`, `requests`) and the environment version bumped to `0.2.6`. This triggers an incremental pixi rebuild -- typically ~30 seconds to 2 minutes for pure-Python packages -- not a full ~1.5-2.5 GB re-download.

**What to do:**
- Wait for the rebuild to complete. The full clustering env (torch, scanpy, etc.) is not re-downloaded; only the new pip packages are installed.
- If the rebuild fails, use **Extensions > QP-CAT > Setup & help > Rebuild analysis environment** to start fresh.

</details>
