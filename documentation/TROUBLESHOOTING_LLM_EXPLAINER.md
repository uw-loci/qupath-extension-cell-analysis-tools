# QP-CAT -- Cluster Explainer (LLM) [Beta] Troubleshooting

This page covers the error states you may see in the **Cluster Explainer (LLM)** tab of the cluster results dialog, what each one typically means, and what to do about it.

The Java side of QP-CAT shows a red status banner on every failure with a one-sentence summary. The full provider response, including any stack traces, is in the project audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the most recent `=== LLM EXPLAIN ===` entry. Both the Java (`LlmAuditScrubber`) and Python (`scrub_secrets`) sides strip `Authorization:` headers and `sk-ant-*` keys from any logged payload, so you can share an audit log without leaking your API key.

See also:

- [How-To Guide section 10](HOW_TO_GUIDE.md#10-explaining-clusters-with-an-llm-beta) -- the full workflow walkthrough
- [Best Practices: When to Use the LLM Cluster Explainer](BEST_PRACTICES.md#when-to-use-the-llm-cluster-explainer) -- when to trust the output and how to cite it

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

**What you see:** The Cluster Explainer (LLM) [Beta] tab opens but the **Run Explainer** button is disabled with a tooltip like *"Marker rankings not available for this clustering result"* or *"Select a provider"*.

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
- If the rebuild fails, use **Extensions > QP-CAT > Utilities > Rebuild Clustering Environment** to start fresh.

</details>
