# LLM cluster explainer [Experimental]


Get a plain-English phenotype suggestion for each cluster, with rationale citing the top markers. Runs on the per-cluster Wilcoxon marker rankings that QP-CAT already produces -- no pixels are sent.

**For research use only.** A suggested phenotype is a hint to check against the markers, not
a diagnosis. It has not been validated for, and must not be used in, diagnostic or clinical
decision-making.

> **What has actually been run, per provider.** *Any chatbot (copy prompt, paste reply)*:
> run end-to-end on 2026-10-12 -- prompt copied from the tab, answered by a Claude model
> outside QuPath, reply pasted back, six clusters from the synthetic demo dataset. *MCP server
> (AI agents)*: run on 2026-10-13, on Linux -- Claude Code registered the server with the
> documented one-liner and `claude mcp list` reported it `Connected`, but never drove a tool; a
> Python MCP client drove all three tools against a real project, and this tab's live update
> was seen in the UI designer's walks on a real window. No other client has connected yet, no
> agent has yet run the three calls unprompted from one typed sentence, and the official
> Windows / macOS installers' bundled runtime has not run it; details on the
> [MCP server](mcp-server.md) page. *Anthropic (Claude API)*: run end-to-end against the live
> API with a real key, on 2026-09-27 -- `claude-sonnet-5`, seven clusters from the synthetic
> demo dataset, 23 seconds. *Ollama*: **never run, by anyone.** It is built from the
> documented API and has not been exercised once, so if you are the first to try it, a
> failure is as likely to be ours as yours -- please
> [report](troubleshooting.md#reporting-a-bug) it.
>
> The two claims are separate. "Never run" is about the code path. *Unvalidated* is about
> the answer, and applies to every provider: the suggestions are proposals to check, not
> labels to publish.

The prompt template, output JSON shape, and audit-log row format may also change.

**Inspiration and prior art.** The design was inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s LLM phenotyping feature. QP-CAT's implementation differs in three notable ways: (a) supports four providers -- any chatbot by clipboard, an MCP-capable agent, Anthropic Claude and local Ollama; OpenAI is intentionally not supported, (b) the LLM reads marker statistics only -- no pixels and no patient-identifying metadata cross the network boundary (the one thing you control: over MCP, the names you gave your saved results travel with the listing), (c) the full prompt and response are captured to a per-project audit log on every call, with API keys scrubbed on both the Java and Python sides before logging.

### When to use this feature

- **vs Rule-Based Phenotyping** -- rule-based gating is deterministic and publication-defensible; the LLM explainer is exploratory. Use the explainer to *propose* phenotype labels, then formalize them as gating rules for the final analysis
- **When the panel is unfamiliar** -- the most direct value. When the panel is new to you, the explainer gives a one-paragraph rationale per cluster where you would otherwise look up each marker
- **When writing up results** -- the audit log captures the full prompt and response, which can be cited verbatim in a methods section ("cluster labels were initially proposed by Claude Sonnet 5 (`claude-sonnet-5`) on $DATE using prompt template `cluster_phenotype_v2`; the full prompt and response are archived in the project log")

### Requirements

Choose one of:

- **Any chatbot you already use** -- claude.ai, ChatGPT, Gemini, Copilot, a local model with a
  chat window. Nothing to install and no key: you carry the prompt over by clipboard and paste
  the reply back. Your own subscription does the work, and nothing leaves QuPath except what
  you paste.
- **An MCP-capable AI agent you already use** -- Claude Code, Codex, Cursor, Gemini CLI. No
  key either: QP-CAT opens a small local server, off until you start it, and the agent does
  the copy-and-paste round trip itself. Setup, the security model and what it cannot do are on
  their own page, [MCP server](mcp-server.md). Claude Code has connected on Linux; the tools have
  been driven by a Python client; other clients unverified.
- **Anthropic Claude** -- a current Anthropic API key from [platform.claude.com](https://platform.claude.com/). Pay-as-you-go (~$0.003-0.015 per cluster set in current Sonnet pricing; see *Cost expectations* below).
- **A running Ollama instance** -- [Ollama](https://ollama.com/) installed locally (or reachable on your network) with at least one chat model pulled. Recommended models: `llama3.1:8b` (4-5 GB, fast, decent), `qwen2.5:14b` (~9 GB, more accurate), or any other model you trust. No API costs; no data leaves your machine.

OpenAI is **not** supported in v1.

### Quick Start

1. Open an image (or project), run **Find cell populations (clustering)...** to completion
2. In the results dialog, open the **Cluster Explainer (LLM) [Experimental]** tab -- it is the **last** tab, after the data tabs
3. In the tab itself, select a provider, model, and (for Anthropic) paste your API key
4. Click **Run Explainer** -- wait 5-30 seconds depending on provider and model. With **Any
   chatbot** the button is replaced by three steps: **1. Copy prompt to clipboard**, paste it
   into the chatbot, paste its reply into the box, **3. Read pasted reply**
5. Read the table (suggested phenotype, confidence, top supporting markers) and select a row to see its one-paragraph rationale in the **Rationale** box below

When the tab is open on a saved result (opened from **View Past Results...**), the suggestions are also saved into that result, so reopening it shows the same table without re-paying the API call. A results window that opened straight after a run has nowhere to write them; they stay in that window and in the audit log.

The **"Use as cluster names..."** button takes the suggested phenotypes into **Modify cell populations** with the rename boxes pre-filled, after a preview of every old → new pair. Names are cleaned for use as class names: a trailing parenthetical like "(CD4+ T cell, inferred)" is dropped (the rationale keeps it), and colons and slashes are replaced with hyphens (a colon makes QuPath read the name as a derived class). Colliding suggestions get a numeric suffix to keep them distinct. Nothing is written by this button -- the rename dialog is where you review them and press Apply, which writes a renamed copy and leaves the original result alone. If you close the rename dialog before pressing Apply, it asks first and says how many names would be lost.

![The Cluster Explainer tab after a run. Provider is set to ANTHROPIC with model claude-sonnet-5, the API key field is masked, and the status line reads "Done. 7 clusters explained (23s)". A Suggestions table gives one row per cluster: proliferating epithelial/tumor cell from Ki67 and PanCK, myofibroblast/smooth muscle cell from aSMA, B cell from CD20, epithelial/tumor cell from PanCK, macrophage from CD68, and cytotoxic T cell from CD8 and CD3, all at HI confidence; helper T cell from CD3 and CD8 at MD confidence. Below, a Rationale box explains cluster 0, and buttons offer Copy results as TSV and Regenerate selected cluster](images/cluster-explainer-suggestions.png)

*Screenshot from an earlier build: the Provider box now reads "Anthropic (Claude API)", and a
third button, **Use as cluster names...**, sits beside the two shown.*

The run above is the one the warning at the top refers to, on the synthetic demo dataset whose
ground truth is known. All seven suggestions matched it. Two details are worth noticing rather
than taking on trust:

- **Cluster 6 came back at `MD` confidence, not `HI`**, and its phenotype reads "Helper T cell
  (CD4+ T cell, **inferred**)". CD4 was never measured in this panel; the call rests on CD3
  being present while CD8 is not. That is the correct inference and the correct hedge, and it
  is the row a reader should check hardest.
- **Clusters 0 and 3 both read as tumor**, separated by proliferation rather than by lineage.
  The explainer sees marker statistics only, so it cannot tell you whether splitting a
  population by cell state was what you wanted.

### Provider Setup

<details>
<summary><strong>Any chatbot (copy prompt, paste reply) -- no key</strong></summary>

1. Pick **Any chatbot (copy prompt, paste reply) -- no key** as the provider. The **Model**
   box becomes a label for the record (`claude.ai`, `ChatGPT`, ...); type or pick whichever
   you are about to use. It starts empty each time, because only you know which chatbot
   answered. It is saved with the result and written to the audit log, so a methods section
   can still say what answered; left empty, the record says the chatbot was not named
   (`Model: (not stated)` in the audit log) rather than guessing.
2. Press **1. Copy prompt to clipboard**. The prompt is the one the API providers get --
   built in the same place, from the same marker rankings, with the same instructions
   (including the rule that no p-values are sent and no significance language is wanted).
   The status line reports its length.
3. Paste it into the chatbot and send. Most chatbots return the JSON list inside a code
   fence with a sentence or two around it; that is fine.
4. Copy the whole reply, paste it into the box under step 2, and press **3. Read pasted
   reply**. The reply goes through the same parser as a live response: a cluster the chatbot
   skipped gets an "LLM did not return a row" line rather than an error, and a reply with no
   JSON in it at all gives that line for every cluster -- the usual cause is the chatbot
   answering in prose, so ask it again for only the JSON array.

What you give up against the API path: token counts (recorded as unknown), and the
one-click re-run of a single cluster becomes copy, paste, paste again. What you gain: it
works with every chatbot that exists, including ones with no API, and no key is ever typed
into QuPath. The audit log entry has provider `CHATBOT`, the label you typed as the model,
and the prompt and reply verbatim. Reopened later from a saved result, the tab shows who wrote
the suggestions above the table, for example `Loaded from the saved result: written by
ChatGPT, as you named it (reply pasted in) at 14:07 on 2026-10-11.`; with the Model box left
empty it reads `a chatbot you did not name (reply pasted in)`.

</details>

<a name="mcp-server"></a>
<details>
<summary><strong>MCP server (AI agents, no key)</strong></summary>

If your AI assistant is an *agent* -- Claude Code, Codex, Cursor, Gemini CLI -- it can do this
round trip itself. You type "explain the clusters in my latest QP-CAT result", the agent asks
QuPath for the prompt, answers it, and writes the names back into this tab. No key, and nothing
copied by hand.

It works by QP-CAT opening a small local server that your agent connects to, and that server is
**off until you start it** -- installing QP-CAT never opens a port, and every QuPath restart
starts with it off. Turning it on is two steps: choose **Extensions > QP-CAT > Setup & help >
MCP server for AI agents**, read the window that opens, then press **Start server**. It listens
on `127.0.0.1` only, requires a token that is generated fresh each time you start it, and stops
by itself when the project changes or after an hour with no authenticated request from your
agent. It offers exactly three tools: list the saved results, read
one result's marker statistics, write cluster explanations back. Nothing reads a file path, an
image, or a cell; nothing runs code.

MCP is not an entry in the **Provider** box, because it is not something you run from this
tab: the agent asks QP-CAT, and the answers appear here, with a line under **Suggestions**
saying which agent wrote them and when. Setup is per client and takes one line of
configuration; the full instructions, the security model and the troubleshooting are on the
[MCP server](mcp-server.md) page. First ask: `list my QP-CAT results`. The agent shows the
saved results of the open project, and the window's status line counts one tool call.

The audit log entry has provider `MCP` and the prompt and reply verbatim -- the same record
the other providers write, with the `Model` row labeled `Agent` and one extra row, `Client`
(the agent program's `User-Agent`). The agent's self-reported label is stored where the other
providers store the model id -- in the saved result's model field and in the audit entry's
`Agent:` row -- so every reader of the log finds it in the same place; QP-CAT records it
without verifying it. The explanations are saved into the result on disk, as they now are for
every provider when the tab is open on a saved result (a live run has nowhere to write). On a
managed install, IT can remove the feature with a launcher option; see
[For IT administrators](mcp-server.md#for-it-administrators).

</details>

<details>
<summary><strong>Anthropic Claude (cloud, paid)</strong></summary>

1. Go to [platform.claude.com](https://platform.claude.com/), create an account if needed, and create a new API key
2. In the explainer tab, set **Provider** to **Anthropic (Claude API)**
3. Set **Model**. The dropdown lists a few current ids as suggestions; it is **editable**, so you can type any id the provider accepts, including a model released after your version of QP-CAT. The id is sent verbatim, and an unrecognized one comes back as a provider error rather than falling back to something else. Anthropic's [model overview](https://platform.claude.com/docs/en/models/overview) lists the current ids.
4. Paste your API key into the **API key** field. The key is held in memory only for this QuPath session and is never written to disk
5. Click **Run Explainer**

**Environment variable shortcut:** set `QPCAT_ANTHROPIC_KEY` to your key before launching QuPath, and the field fills itself. This is the better setup on a machine you use regularly -- the key follows your user account rather than being retyped every session.

QuPath reads the variable **once, at launch**, so set it first and start QuPath afterwards. Launching from a terminal that has the variable also works.

<details>
<summary>How to set it, per platform</summary>

**Windows (persistent).** In a Command Prompt:

```
setx QPCAT_ANTHROPIC_KEY "sk-ant-..."
```

`setx` writes to your user environment, so it survives a reboot -- but it does **not** affect the window you typed it in. Close that window, then start QuPath. The GUI equivalent is Settings > System > About > Advanced system settings > Environment Variables > New under "User variables".

**Windows (this session only).** `set QPCAT_ANTHROPIC_KEY=sk-ant-...`, then launch QuPath from that same Command Prompt.

**macOS / Linux (persistent).** Add to `~/.zshrc` (macOS default) or `~/.bashrc`:

```
export QPCAT_ANTHROPIC_KEY="sk-ant-..."
```

Then open a new terminal and start QuPath from it. An app launched from the Dock or a desktop icon does **not** read your shell profile, so on macOS either launch QuPath from the terminal or use `launchctl setenv QPCAT_ANTHROPIC_KEY "sk-ant-..."` for GUI launches.

**Check it took.** `echo %QPCAT_ANTHROPIC_KEY%` (Windows) or `echo $QPCAT_ANTHROPIC_KEY` (macOS / Linux) in a NEW terminal. If that prints nothing, QuPath will not see it either.

A key set this way is stored in plain text in your user environment. On a shared or managed machine, prefer pasting it into the field each session.

</details>

**Note:** the API key field is session-scoped -- there is no "remember this key" checkbox, and no keychain integration. Re-enter it, or set the environment variable.

</details>

<details>
<summary><strong>Ollama (local, free)</strong></summary>

1. Install [Ollama](https://ollama.com/) on your machine (or a machine reachable from this one)
2. Pull a chat model: `ollama pull llama3.1:8b` (or any other model)
3. Confirm Ollama is running: `curl http://localhost:11434/api/tags` should list your installed models
4. In the explainer tab, set **Provider** to **Ollama (local) -- never run**
5. The **Endpoint** field is pre-populated with `http://localhost:11434` -- the standard Ollama default. Change it only if your Ollama server runs on a different host or port
6. Set **Model** to the exact tag you pulled (e.g. `llama3.1:8b`). The dropdown suggests `llama3.1:8b` and `phi3:14b`; type the exact tag you pulled if it is anything else
7. Click **Run Explainer**

**Tips:**
- Quality varies widely by model. A 7-8B parameter model is usually good enough for a "what cell type is this cluster" question; a 1-3B model often hallucinates marker associations
- The first call after `ollama pull` may be slow while the model warms up; subsequent calls are typically 5-15 seconds
- Remote Ollama is fine: set the endpoint to `http://<host>:11434`. Be aware that traffic is unencrypted by default; restrict access at the network level

</details>

### What the LLM Sees

The prompt -- one text for every provider, built once by `build_prompt` in
`run_llm_explainer.py` -- contains, per cluster:

- The cluster id (e.g. `Cluster 3`)
- The top-N (default 10) markers by Wilcoxon score, each as the marker name, its score and its log2 fold change. **Adjusted p-values are deliberately NOT sent** -- the clusters were defined from these same measurements, so the p-value is circular, and handing it to a language model invites it to write "significantly enriched". The prompt explicitly instructs the model not to claim significance. See [the marker p-values are circular](clustering.md#marker-pvalues-are-circular).

The prompt **does not** contain:
- A cell count, or a count of anything
- A cluster-by-marker expression table (the model reasons from the ranked markers alone)
- Pixel data of any kind
- Individual cell measurements
- Image metadata, file names, or paths
- Patient identifiers, sample names, or any project metadata
- Spatial coordinates of cells
- Anything from QuPath beyond the ranked markers per cluster

That list is about the **prompt**. Over the MCP server the agent also sees the result listing
-- result names you chose, timestamps, cluster and cell counts, and a coarse scope such as
`1 image` -- which the MCP page itemizes under
[What text does leave QuPath](mcp-server.md#the-three-tools-and-exactly-what-each-one-can-do).

You can see the exact prompt that was sent by opening the audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` and finding the `=== LLM EXPLAIN ===` entry. The prompt is reproduced verbatim in the indented `Prompt:` block. Both the Java and Python sides scrub `Authorization:` headers and `sk-ant-*` keys before any payload is logged, so the audit trail never contains the API key.

### Interpreting the Output

Each row of the result table has the columns below. Select a row to read the model's
one-paragraph rationale for it in the **Rationale** box under the table.

| Column | Meaning |
|---|---|
| **Cluster** | The cluster id (`0`, `1`, ...) |
| **Suggested phenotype** | The LLM's primary phenotype guess (e.g. "CD8+ cytotoxic T lymphocyte"). May show **(no suggestion)** -- see "Refused-to-guess rows" below |
| **Confidence** | `HI`, `MD` or `LO`: the model's own `high` / `medium` / `low` from the reply (`med` and `moderate` are read as `MD`). Blank when the model set it to null **or used any other word**; the `Response:` block in the audit log keeps the original. Treat with skepticism; `HI` does not mean correct |
| **Top supporting markers** | Top markers the LLM cited as evidence (e.g. "CD3, CD8, GZMB") |

**Refused-to-guess rows ("(no suggestion)").** The LLM is allowed to emit `phenotype: null` for a cluster when the marker signature is too weak or incoherent to support a guess. This is **expected behavior**, not an error -- the result table shows **(no suggestion)** in the Suggested phenotype column and the Rationale box still explains *why* the model refused (e.g. "insufficient signal: the top markers are mutually inconsistent, spanning myeloid and epithelial lineages"). Treat these rows as a useful signal that the cluster itself may need a closer look, not as a failure of the explainer.

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
- Over MCP or the chatbot the sampling settings are the agent's or the chat window's; QP-CAT cannot pin them

The audit log captures the full prompt and response for every call. For a paper-grade trail:

1. Record the **exact provider and model string** from the audit log entry (e.g. `claude-sonnet-5`). For an MCP run no row records the model; take it from the agent's own session and report the `Agent:` row as the agent's self-description beside it
2. Record the **prompt template version** (e.g. `cluster_phenotype_v2`)
3. Archive the **`Response:` block** verbatim -- this is the actual text the LLM returned, including any cluster suggestions you accepted into your final analysis

The goal is **reproducibility of input** -- anyone reading your paper can run the same prompt against the same model and judge the answer for themselves. Reproducibility of *output* is not a property the LLM provides.

### Cost Expectations

One **Run Explainer** click is one LLM call with all clusters batched into the same prompt. Rough order-of-magnitude per click (Anthropic, current Sonnet pricing):

| Clusters | Top markers per cluster | Approx. input tokens | Approx. output tokens | Approx. cost |
|---|---|---|---|---|
| 5 | 10 | 1,500 | 800 | ~$0.005-0.01 |
| 10 | 10 | 3,000 | 1,500 | ~$0.01-0.02 |
| 20 | 10 | 6,000 | 3,000 | ~$0.02-0.05 |

Pricing changes; the audit log captures the exact `Input tokens:` and `Output tokens:` for every call (Anthropic and Ollama only; the chatbot and MCP paths record `unknown`) so you can compute real spend at the right rate. (Anthropic charges different rates for input vs output -- Sonnet output is ~5x input -- so a combined number alone undercounts cost.) The combined `Token count:` is still emitted for backward compatibility. Ollama is free.

A **Cancel** button is exposed during the in-flight call. A cancelled call is not written to the project audit log -- only a QuPath log line, `LLM explainer cancelled; not applying results.`, records it -- but it **may still consume tokens depending on provider and request stage; check your billing**. The cancel is a "soft" cancel on the Java side -- the Python HTTP request is allowed to complete in the background -- so an already-sent request may still be billed for input tokens even if you stop reading the response.

### [Experimental] notice

**Status: three providers run, one not.** The chatbot, MCP (a Python test client, Linux only;
Claude Code has connected but has not yet run the three calls itself) and Anthropic paths have
each been run end-to-end. The Ollama path has
not been run at all, so treat everything below about Ollama as design intent rather than
observed behavior. No provider's *output* has been validated against ground truth beyond
the seven-cluster synthetic check above.

This is the first feature in QP-CAT that calls a remote LLM API. The surface area is
intentionally narrow:

- One prompt template (`cluster_phenotype_v2`); not user-editable yet
- Four providers (any chatbot, MCP agent and Anthropic, each run; Ollama, never run); OpenAI deferred
- One batched call per Run Explainer click; per-cluster async deferred
- API key is session-scoped; OS-keychain integration deferred

The audit-log entry, result-table JSON and prompt template are not stable surfaces. If you build tooling against them, expect to adjust.

Both the Java side (`LlmAuditScrubber`) and the Python side (`scrub_secrets` in `run_llm_explainer.py`) redact `Authorization:` headers and `sk-ant-*` keys before any payload reaches the audit log -- a tested invariant covered by `LlmKeyRedactionTest`.

---

## When it is worth using


The LLM cluster explainer sits alongside Rule-Based Phenotyping and clustering as a complementary tool. It operates on **per-cluster statistics** rather than per-cell data.

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

Every LLM call is logged to `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the `=== LLM EXPLAIN ===` entry tag with provider, model, the images the result covers (`Image:` names the image for a one-image result run from the tab and reads `8 images` for an eight-image one; over MCP it is the coarse scope, `1 image`, `8 images` or `unknown scope`, never a name; a result saved without per-cell image references records the image that was open), prompt-template version, prompt text, response text, and token counts (`unknown` for the chatbot and MCP paths). Both the Java side (`LlmAuditScrubber`) and the Python side (`scrub_secrets`) strip `Authorization:` headers and `sk-ant-*` keys from any payload before it reaches the log, so the audit trail does not contain the API key. It is append-only plain text, not tamper-evident, and the scrubber removes credential shapes only -- it does not de-identify anything else, such as a result name you typed. For any paper that uses LLM-derived phenotype labels (even just as initial hypotheses), include in your methods section:

- **Provider and exact model string** (e.g. `claude-sonnet-5`, not just "Claude")
- **For an MCP run: QP-CAT does not know which model answered.** The `Agent:` row is a label the agent wrote about itself (cut at 60 characters) and the `Client:` row is the client program's `User-Agent`, not a model. Take the model from the agent's own session (the model shown in the client, or its transcript) and report the `Agent:` label as the agent's self-description beside it. The recorded prompt is the text QP-CAT handed the agent; the model saw it inside the agent's conversation, with a system prompt and tool descriptions QP-CAT does not record, and with sampling settings QP-CAT neither sets nor sees. The `Agent:` and `Client:` rows are written as sent, unscrubbed
- **Prompt template version** as logged (currently `cluster_phenotype_v2`)
- **The fact that the call was made** -- LLM involvement, even at the exploratory stage, should be disclosed
- **Whether final phenotype labels were taken directly from the LLM output or re-derived from rule-based gating** -- these are very different reproducibility stories

Archive the audit log alongside your other reproducibility artifacts (clustering configs, rule sets, exported AnnData). The plain-text format is intentionally diff-friendly and version-control-friendly.

---

## Troubleshooting


> **The error states below are derived from the code, not from observed
> failures**, so the list is neither complete nor confirmed. The runs on record
> exercised none of them.

This page covers the error states you may see in the **Cluster Explainer (LLM)** tab of the cluster results dialog, what each one typically means, and what to do about it.

On every failure the status line under the Run button reads `Failed: ...` with a one-sentence summary. The full provider response, including any stack traces, is in the project audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the most recent `=== LLM EXPLAIN ===` entry. Both the Java (`LlmAuditScrubber`) and Python (`scrub_secrets`) sides strip `Authorization:` headers and `sk-ant-*` keys from any logged payload, so you can share an audit log without leaking your API key.

See also:

- [Quick Start](#quick-start) -- the full workflow walkthrough
- [above](#when-it-is-worth-using) -- when to trust the output and how to cite it
- [MCP server troubleshooting](mcp-server.md#troubleshooting) -- connection refused, 401, 403,
  port in use, and the messages an agent relays; none of them are repeated here

---

<details>
<summary><strong>"No network connection" or "Could not reach Anthropic"</strong></summary>

**What you see:** The Run Explainer button completes quickly and the status line reads *"Failed: cannot reach the provider."*

**What it usually means:** Your machine cannot reach `api.anthropic.com` over HTTPS. Most common causes: laptop is offline, corporate firewall/proxy is blocking the request, or DNS is misconfigured.

**What to do:**
1. Test connectivity in a terminal: `curl -I https://api.anthropic.com/v1/messages` should return an HTTP response (401 is fine -- it means the host is reachable; the auth just failed)
2. If you're behind a corporate proxy, ensure Java picks it up. Pass `-Dhttps.proxyHost=...` `-Dhttps.proxyPort=...` to QuPath at launch
3. As a fallback, use the Ollama provider for an offline-capable path

</details>

<details>
<summary><strong>"Invalid API key" or "401 Authentication failed"</strong></summary>

**What you see:** The status line reads *"Failed: 401 -- API key was rejected."*

**What it usually means:** The key was mistyped, has been revoked, or has been rotated and the old value is still in the TextField or env var.

**What to do:**
1. Go to [platform.claude.com](https://platform.claude.com/) and verify the key is listed and active
2. Re-copy the key (watch for trailing whitespace) and paste again
3. If using the `QPCAT_ANTHROPIC_KEY` environment variable, restart QuPath so the new value is picked up -- the env var is read once at launch

</details>

<details>
<summary><strong>"Rate limit exceeded" or "429"</strong></summary>

**What you see:** The status line reads *"Failed: 429 -- provider rate limit hit."* The Run button re-enables immediately; the provider's own `Retry-After` header is not currently surfaced in the UI.

**What it usually means:** You've made too many calls in too short a window, or your account is on a free/trial tier with low per-minute limits.

**What to do:**
1. Wait the suggested duration and click Run Explainer again
2. If this happens repeatedly, check your account's rate-limit tier in the Anthropic console
3. Ollama has no rate limits; switch providers if you're prototyping prompts at high volume

</details>

<details>
<summary><strong>"Malformed response" or "Could not parse LLM output"</strong></summary>

**What you see:** The status line reads *"Failed: provider returned an unexpected response shape."* The full response is in the audit log; please file an issue.

**What it usually means:** The LLM did not produce JSON in the expected schema. Often happens with Ollama + a small/weak model, or after a provider-side change to default behavior.

**What to do:**
1. Open `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` and find the most recent `=== LLM EXPLAIN ===` entry. The `Response:` block contains the actual text the LLM returned
2. If the response looks plausible but isn't valid JSON, try a stronger model (Anthropic Sonnet, or Ollama with a larger model)
3. If the response is gibberish, the model may not be capable of this task -- switch models
4. File an issue with the audit-log entry attached so the prompt template can be hardened

**Not the same as "(no suggestion)":** A row showing **(no suggestion)** in the result table is *not* a malformed response. The LLM is allowed to emit `phenotype: null` with a rationale ("insufficient signal") when a cluster's marker signature is too weak or incoherent to support a guess. That is expected behavior, not an error -- see [Interpreting the Output](#interpreting-the-output).

</details>

<details>
<summary><strong>"Could not reach Ollama at &lt;endpoint&gt;"</strong></summary>

**What you see:** The status line reads *"Failed: cannot reach the provider."* (This is the same message the Anthropic-network-down case uses; the audit-log entry's `Endpoint:` row and `Error detail:` line will name Ollama and the URL it could not reach.)

**What it usually means:** The Ollama server is not running, has crashed, or is listening on a different host/port.

**What to do:**
1. In a terminal: `curl http://localhost:11434/api/tags` -- this should return JSON listing your installed models
2. If you get a connection-refused error, start Ollama: `ollama serve` (or use the Ollama app's tray icon)
3. If you're pointing at a remote Ollama, verify the host is reachable: `curl http://<host>:11434/api/tags` from this machine
4. Update the **Endpoint** field in the explainer tab and click Run again

</details>

<details>
<summary><strong>"Ollama model not found" or "model '&lt;tag&gt;' not pulled"</strong></summary>

**What you see:** The status line reads *"Failed: model not found on provider."* The audit-log `Error detail:` line names the missing tag and suggests `ollama pull <tag>`.

**What it usually means:** The model tag you selected (or typed) is not present on the Ollama server.

**What to do:**
1. In a terminal on the Ollama host: `ollama list` -- shows installed models
2. Pull the missing model: `ollama pull llama3.1:8b` (or the tag you want)
3. Wait for the pull to complete -- this can be slow for large models (8B ~ 5 GB, 70B ~ 40 GB)
4. Click Run Explainer again

</details>

<details>
<summary><strong>"Cancelled. No results applied." / Cancel button was clicked</strong></summary>

**What you see:** The status line reads *"Cancelled. No results applied."*

**What it usually means:** You clicked Cancel while the call was in flight, or QuPath was closed before the call returned.

**What to do:**
- Nothing -- this is the expected behavior. A cancelled call is not written to the project audit log; the QuPath log has the line `LLM explainer cancelled; not applying results.`. The cancel is a "soft" cancel on the Java side -- the underlying HTTP request is allowed to complete in the background -- so cancelled calls **may still consume tokens depending on provider and request stage; check your billing**. Anthropic, in particular, may bill for input tokens on a request that was already in flight.
- Click Run Explainer again when ready.

</details>

<details>
<summary><strong>Run Explainer is disabled, or the tab is missing</strong></summary>

**What you see:** Either there is no Cluster Explainer (LLM) [Experimental] tab in the results window, or the tab opens but **Run Explainer** is disabled. The status line may read *"Choose a provider to begin."*

**What it usually means:** No tab means the result has no Wilcoxon marker rankings (a result with only one group, or a partial run), so there is nothing to send. A disabled button means the **Provider** is **None**, the **API key** box is empty (Anthropic), or nothing has been pasted yet (Any chatbot).

**What to do:**
1. No tab: re-run clustering; marker rankings are computed for every standard algorithm, and the [quality warnings](results.md#marker-rankings-and-fingerprints) at the top of the results window say why they are missing
2. Disabled button: pick a provider from the **Provider** dropdown (Any chatbot, Anthropic or Ollama) and give it what it needs. The provider config lives in the tab -- there is no global "set this once in Preferences" step to do separately

</details>

<details>
<summary><strong>The Python environment rebuilds on first upgrade</strong></summary>

**What you see:** (Only when upgrading from a QP-CAT older than 0.2.6.) The first time you launch QuPath after upgrading to a QP-CAT version that includes the LLM explainer, the QP-CAT Python environment performs a one-time additive rebuild as Appose detects the new pixi.toml. The clustering UI will not be available until the rebuild completes.

**What it usually means:** The Appose Python env's `pixi.toml` gained two new pure-Python dependencies (`anthropic`, `requests`) and the environment version bumped to `0.2.6`. This triggers an incremental pixi rebuild -- typically ~30 seconds to 2 minutes for pure-Python packages -- not a full ~1.5-2.5 GB re-download.

**What to do:**
- Wait for the rebuild to complete. The full clustering env (torch, scanpy, etc.) is not re-downloaded; only the new pip packages are installed.
- If the rebuild fails, use **Extensions > QP-CAT > Setup & help > Rebuild analysis environment** to start fresh.

</details>
