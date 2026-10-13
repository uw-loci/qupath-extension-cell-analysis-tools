# MCP server for the Cluster Explainer [Experimental]

Let an AI agent you already use -- Claude Code, Codex, Cursor, Gemini CLI -- read a QP-CAT
clustering result and write cluster names back, without an API key in QuPath and without
copying anything by hand.

> **What has actually been run.** On 2026-10-13, on Linux: a Python MCP client drove all
> three tools against a real project from a hidden QuPath (the harness scenario), and Claude
> Code registered the server with the one-liner below and reported it `Connected`; the
> Explainer tab updated live while a saved result was open. **No other client has connected
> yet** -- Codex, Cursor, Gemini CLI, VS Code and Claude Desktop are written from each
> client's published format and have not been exercised, on any platform, so if you are the
> first to try one, a failure is as likely to be ours as yours -- please
> [report](troubleshooting.md#reporting-a-bug) it.
>
> Two claims, kept separate. "Never run" is about the code path. *Unvalidated* is about the
> answer, and applies to every provider: a suggested phenotype is a proposal to check, not a
> label to publish.

## What this is

[MCP](https://modelcontextprotocol.io) -- the Model Context Protocol -- is an open standard
that lets an AI assistant call tools in programs running on your machine, the way a plugin
works. QP-CAT can offer three such tools over it. In plain terms: you type "explain the
clusters in my latest QP-CAT result" into your AI coding assistant, the assistant asks QuPath
for the cluster summary, writes the answer itself, and hands the names back -- and the Cluster
Explainer tab fills in. Your own subscription does the thinking. No API key is typed into
QuPath, nothing is copied by hand, and the server that makes this possible is **off until you
turn it on**.

The design follows two pieces of prior art. [`fiji_mcp`](https://github.com/NicoKiaru/fiji_mcp)
established the shape: the chatbot is the agent and the imaging application is a tool
provider. [`qupath-mcp`](https://github.com/earthmover-support/qupath-mcp) hosts an MCP
server inside QuPath, and its
[per-client install pages](https://earthmover-support.github.io/qupath-mcp/install/) are the
model for the snippets below. QP-CAT's server differs on purpose in the ways the
[Security](#security) section describes, and the two are independent -- see
[Running both servers](#running-both-qp-cats-server-and-qupath-mcp).

An *agent* is not the same thing as a *chatbot*. An agent is a client that can call tools; a
chatbot is a window you type into. If your AI assistant cannot be given an MCP server in its
configuration, use the clipboard provider on the
[LLM explainer](llm-explainer.md#provider-setup) page instead. It is not a lesser path.

## Security

Read this section before you turn the server on. Everything the server can and cannot do is
stated here, in five short parts.

### It is off, on every installation

Installing or updating QP-CAT never opens a port. Only you can start the server, from
**Extensions > QP-CAT > Setup & help > MCP server for AI agents** followed by the
**Start server** button in the window that opens -- and nothing else does: not QuPath launch,
not opening a project, not running a clustering analysis. There is no preference that
remembers "on", by design: the on/off state lives in memory only, so every QuPath restart
begins with the server off. A "start with QuPath" option does not exist, deliberately, and is
not planned for this release.

### Where it listens, and who it answers

| Property | What QP-CAT does | Why |
|---|---|---|
| Address | Binds `127.0.0.1` (loopback) only, never `0.0.0.0`. Port **51516**; if that is taken it tries 51517 through 51525 and tells you which one it bound | Nothing outside this machine can reach it, including other machines on your lab network or VPN. 51516 is not `qupath-mcp`'s 51515, so both can run |
| `Origin` and `Host` headers | A request whose `Origin` or `Host` is present and is not loopback is refused with **403** | A loopback bind alone does not stop a web page you visit from posting to the port from your browser. The MCP specification makes the `Origin` check a MUST ([transports, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)) |
| Token | A fresh random token is generated every time you start the server, shown in the window for as long as that server is running, and required on every request as `Authorization: Bearer <TOKEN>`. A request without it is refused with **401** | A stale config file cannot be a credential: the token never persists, is never written to any log, and changes on every start |
| Lifetime | The server stops by itself when the project changes or closes, and after 60 minutes with no connection or tool call. QuPath exiting ends it too, since nothing remembers it was on | The tools are scoped to one project; switching projects must not silently repoint your agent at a different one. The idle stop covers the server you forgot about |
| Rate | At most 30 tool calls per minute, and one `tools/call` in flight at a time; the rest are refused with a message, not queued | The specification requires rate limiting; one-at-a-time matches QP-CAT's own one-Python-task-at-a-time rule |

### The three tools, and exactly what each one can do

| Tool | Arguments | Reads | Writes |
|---|---|---|---|
| `qpcat_list_results` | none | The clustering results saved in the open project, as a JSON array: name, timestamp, one-line summary, scope, size, what it was derived from, and whether it already has explanations | Nothing |
| `qpcat_explain_prompt` | `result` (a name from the list above), optional `top_n`, optional `cluster_ids` | One saved result's per-cluster marker rankings; returns the explainer prompt QP-CAT would send to any other provider, then a short metadata block with the prompt's hash | Nothing |
| `qpcat_set_explanations` | `result`, `response` (the agent's reply text), `agent` (a label for the record), optional `overwrite` | The agent's reply | The cluster explanations, into that saved result and into the project audit log |

Those marker rankings are the same statistics every other provider sees, built by the same
code: cluster ids, cell counts, the top-N markers by Wilcoxon score with score and log fold
change, and the cluster-by-marker mean expression table. See
[What the LLM sees](llm-explainer.md#what-the-llm-sees) -- that section is the authority and
this page does not restate it.

If a result already has explanations, `qpcat_set_explanations` refuses unless the agent passes
`overwrite: true`, and its refusal names who wrote the existing ones and when. A careful agent
never hits this, because `qpcat_list_results` already says which results have explanations; a
careless one leaves an explicit, logged choice in its own transcript, which is what a PI
auditing the run needs. The previous explanations stay in the audit log either way.

### What it cannot do

- **No file paths.** No tool takes a path, a URL, or a filename. The only way to name a result
  is to pick one of the names `qpcat_list_results` returned, matched exactly; a name that is
  not on that list is refused without touching the disk, and the refusal never echoes a path.
- **No code.** Nothing runs Groovy, a script, or a command. This is the one deliberate
  difference from `qupath-mcp`, whose
  [security page](https://earthmover-support.github.io/qupath-mcp/security/) states plainly
  that its tools can run any code in QuPath with your privileges and file access.
- **No pixels, no images, no cells.** No tool reads an image, a tile, a thumbnail, or an
  individual cell measurement.
- **No deleting, no renaming, no reclustering.** The single write is the explanation text on
  one saved result.
- **No environment build.** If QP-CAT's Python environment is not running, the tool says so
  and stops. A network request cannot trigger a minutes-long install.

**Once connected, the agent acts on your behalf -- within those three tools, and nowhere
else.** It can list your saved results, read one result's marker statistics, and write cluster
explanations into it. That is the entire surface.

### Residual risk, stated plainly

With loopback, the `Origin` check, and the token in place, the remaining exposure is another
program running as you, on this machine, that can read the token. Since the token is in no
log and no preference, that program would have to read the window or QuPath's memory, at
which point it could read your project files directly anyway. That is where the risk bottoms
out, and it is a sentence rather than a claim of isolation. Two things worth acting on: do not
turn the server on a machine you share with people you would not hand your project to, and
turn it off when you are done. Whether a clinical or managed QuPath may run it at all is a
policy question for whoever manages that machine, not a setting in QP-CAT.

## Turning it on and off

Turning it on is two steps; turning it off is one.

1. Choose **Extensions > QP-CAT > Setup & help > MCP server for AI agents (off)**. It is the
   last item in that submenu. A window titled **QP-CAT MCP Server** opens. **The server is not
   running yet.** The window leads with an amber banner saying what you are about to start,
   and a block headed CAN / CANNOT / REACH / LOGS that restates the [Security](#security)
   section in four lines. A project must be open; with none open you get an error instead,
   and nothing starts.
2. Press **Start server**. The status line changes to `RUNNING since HH:mm - 0 tool calls so
   far`, the **Address** field shows `http://127.0.0.1:51516/mcp` (or the fallback port, named
   in the status line), and the **Token** field shows the token. Each has a **Copy** button.
   The menu item now reads **MCP server for AI agents (ON - port 51516)** with a check mark,
   and the QuPath log gains one line, `MCP endpoint listening on http://127.0.0.1:51516/mcp`.
   The token is never in the log.
3. Copy the address and the token into your agent's configuration -- the next section has
   one block per client -- or copy the ready-made command from the **Connect your agent**
   pane.
4. To turn it off: untick the menu item, or press **Stop server** in the window. One click,
   either way. Closing the window with **Close window** or Esc does **not** stop the server;
   the button's tooltip says so, and the menu item keeps reading `(ON - port ...)`.

**About the token.** It is shown for as long as that server session runs. If you close the
window while the server is on, a second item appears under the tick, **Show MCP server window
(address and token)**, which reopens it with the same token (the tick itself would stop the
server, so it is not the way back in). A new token exists only after you stop and start the
server, at which point any configuration holding the old one stops working, and you paste the
new one. The token is never written to QuPath's log, your
preferences, or any file; the window is the only place it appears.

The tool-call counter in the status line is the ambient reassurance. If it still reads
`0 tool calls so far` after an hour, nothing has used the server.

When the server stops for a reason you did not ask for, the window's status line says why,
the menu item returns to `(off)`, and the log line `MCP endpoint stopped (reason: ...)` names
the reason: `user turned it off`, `project changed`, or `idle for 60 minutes`. The idle clock
restarts on every accepted connection or tool call, and is checked once a minute. A project
change also shows a notification, because you may not have the window open. Quitting QuPath
ends the server without a log line of its own; it is off at the next launch, because
nothing remembers it was on.

## Connecting your agent

Each block below is one client. `<PORT>` is the port the window shows (51516 unless the
status line says otherwise) and `<TOKEN>` is the token it shows.

> **Verification status: no snippet on this page has been run against this server.** They
> are written from each client's own published configuration format and from `qupath-mcp`'s
> [install page](https://earthmover-support.github.io/qupath-mcp/install/), which documents
> the same transport for the same kind of server. Each block carries its own status line. A
> line reading "verified on <date> with <client> <version>" means somebody did it; until a
> block says that, treat it as a well-informed guess and keep your client's own documentation
> open beside it.

<details>
<summary><strong>Claude Code</strong> -- one command</summary>

*Status: run on 2026-10-13 on Linux -- this command registered the server and `claude mcp
list` reported it `Connected`; after Stop server it reported the connection refused. Flags
verified against `claude mcp add --help` on 2026-10-12 (`-t/--transport http`, `-H/--header`,
`-s/--scope`).*

```
claude mcp add --transport http qpcat http://127.0.0.1:<PORT>/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

The window's **Connect your agent > Claude Code** pane shows this command with the real port
and token filled in, and a **Copy command** button. Run it in a terminal, in the folder you
work from. Add `-s user` to register the server for every folder instead of only that one
(the default scope is `local`, the current folder). Then type `/mcp` in Claude Code to see
whether `qpcat` is connected and lists three tools.

Because the token changes every time you start the server, you will re-run this command, or
edit the saved entry, after each restart. `claude mcp remove qpcat` clears the old one.

</details>

<details>
<summary><strong>Any other MCP client</strong> -- the two values it needs</summary>

*Status: this block cannot go stale, because it carries no command.*

Every MCP client that supports a streamable-HTTP server with a custom header needs exactly
these:

| | Value |
|---|---|
| Transport | Streamable HTTP. One endpoint, POST only. The server answers plain JSON, not an event stream |
| URL | `http://127.0.0.1:<PORT>/mcp` |
| Header name | `Authorization` |
| Header value | `Bearer <TOKEN>` |

The window's **Any other MCP client** pane shows these as three copyable fields. Your client's
own documentation says where they go.

</details>

<details>
<summary><strong>Claude Desktop</strong> -- via <code>mcp-remote</code></summary>

*Status: unverified. Never run.*

Claude Desktop is not known to reach a local HTTP MCP server directly; it reads a command to
launch, not a URL. `qupath-mcp` documents the same limitation and the same workaround, a
bridge process. In `claude_desktop_config.json` (macOS:
`~/Library/Application Support/Claude/claude_desktop_config.json`; Windows:
`%APPDATA%\Claude\claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "qpcat": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:<PORT>/mcp",
               "--header", "Authorization: Bearer <TOKEN>"]
    }
  }
}
```

`mcp-remote` is third-party software, not ours and not Anthropic's; installing it is your
call. Restart Claude Desktop after editing the file.

</details>

<details>
<summary><strong>Codex</strong></summary>

*Status: unverified. Never run.*

`~/.codex/config.toml`:

```toml
[mcp_servers.qpcat]
url = "http://127.0.0.1:<PORT>/mcp"
http_headers = { "Authorization" = "Bearer <TOKEN>" }
```

If your Codex version names the header table differently, the
[Any other MCP client](#connecting-your-agent) block has the raw values.

</details>

<details>
<summary><strong>Cursor</strong></summary>

*Status: unverified. Never run.*

`~/.cursor/mcp.json` for every project, or `.cursor/mcp.json` inside one project:

```json
{
  "mcpServers": {
    "qpcat": {
      "url": "http://127.0.0.1:<PORT>/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

</details>

<details>
<summary><strong>Gemini CLI</strong></summary>

*Status: unverified. Never run.*

`~/.gemini/settings.json`. Note that this client uses `httpUrl`, not `url`, for a
streamable-HTTP server:

```json
{
  "mcpServers": {
    "qpcat": {
      "httpUrl": "http://127.0.0.1:<PORT>/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

</details>

<details>
<summary><strong>VS Code</strong></summary>

*Status: unverified. Never run.*

`.vscode/mcp.json` in the workspace, or the **MCP: Open User Configuration** command:

```json
{
  "servers": {
    "qpcat": {
      "type": "http",
      "url": "http://127.0.0.1:<PORT>/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

</details>

### Running both QP-CAT's server and `qupath-mcp`

They are separate servers on separate ports and do not interfere: QP-CAT's default is
**51516**, `qupath-mcp`'s is **51515**. Give them different names in your client
configuration (`qpcat` and `qupath`) so your agent can tell which tools belong to which. Note
the difference in what they expose: `qupath-mcp` can run arbitrary Groovy in QuPath; these
three tools cannot run anything. Running both at once has not been tried.

## A worked example

You have a saved clustering result open from **Extensions > QP-CAT > Results & populations >
View Past Results...**, with the results window on the **Cluster Explainer (LLM)
[Experimental]** tab. You have started the server and registered it with Claude Code. In
Claude Code you type:

> Explain the clusters in my latest QP-CAT result

What happens, in order. The outputs below are illustrative -- the exact wording is set by the
code and has not been observed yet.

1. **The agent calls `qpcat_list_results`.** It gets back the saved results in the open
   project as one JSON array, one object per result (values illustrative; the field names are
   the code's):

   ```json
   [
     {"name": "leiden_res1.0_2026-10-11", "timestamp": "2026-10-11T16:42:08",
      "summary": "leiden, 7 clusters, 48,210 cells", "scope": "current image",
      "autoSaved": false, "sizeBytes": 31457280, "hasExplanations": false},
     {"name": "kmeans_k8_2026-10-09", "timestamp": "2026-10-09T11:05:51",
      "summary": "kmeans, 8 clusters, 48,210 cells", "scope": "current image",
      "autoSaved": true, "sizeBytes": 29360128, "hasExplanations": true}
   ]
   ```

2. **The agent calls `qpcat_explain_prompt`** with `result: "leiden_res1.0_2026-10-11"`. It
   gets back two text blocks. First the prompt QP-CAT would have sent to any other provider,
   verbatim -- the marker table and the instructions, including the rule that no p-values are
   sent and no significance language is wanted. Then a metadata block:

   ```
   prompt_hash: <hash>
   prompt_template: cluster_phenotype_v2
   result: leiden_res1.0_2026-10-11
   clusters: all
   Next step: answer the prompt above in the exact JSON shape it asks for, then call qpcat_set_explanations with this result name, your full reply as "response", and a short "agent" label for the audit log.
   ```

3. **The agent answers it.** This is the part your subscription pays for; QuPath is not
   involved, and no call leaves your machine from QuPath. The agent produces the JSON array of
   per-cluster suggestions the prompt asks for.

4. **The agent calls `qpcat_set_explanations`** with that reply and a label such as
   `agent: "Claude Code (claude-fable-5-1)"`. QP-CAT parses the reply with the same parser the
   clipboard and API paths use, writes the explanations into the saved result, and writes the
   prompt and reply to the project audit log. The tool result tells the agent what happened,
   including whether anything on screen changed:

   ```
   Saved 7 suggestions to the clustering result "leiden_res1.0_2026-10-11".

   A results window showing this result is open in QuPath; its "Cluster Explainer (LLM) [Experimental]" tab has been updated.
   ```

   A cluster the agent skipped gets an "LLM did not return a row" line in the table rather
   than an error, exactly as on the other providers. A reply with no JSON array in it at all
   is refused and nothing is stored; see [Troubleshooting](#troubleshooting).

Where you see the result:

- **The Cluster Explainer tab**, if that result is open in a results window: the table
  repopulates, a line appears under the **Suggestions** heading reading `Last updated by
  Claude Code (claude-fable-5-1) at 14:07, over MCP -- by an AI agent, not from this panel.
  Saved to the result on disk.`, and the status line reads `An AI agent just wrote 7
  suggestions over MCP. Scroll the table to review them.` No popup, no toast. If no results
  window shows that result, the tool result says so instead and names the menu path, and you
  open the result from **View Past Results...** to see them.
- **The audit log**, at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log`, under the
  `=== LLM EXPLAIN ===` entry: provider `MCP`, the agent label as the model, the prompt and
  the reply verbatim. The same record every other provider writes, which is what lets a
  methods section say what answered. See
  [Audit-log discipline for publications](llm-explainer.md#audit-log-discipline-for-publications).
- **The saved result**, so reopening it later shows the same table. This is new: before this
  feature, explanations lived only in the open window and the audit log, for every provider.

A second, narrower thing to try first: "list my QP-CAT results". That calls only
`qpcat_list_results`, writes nothing, and is the cheapest way to confirm the connection works.

## Which of the three providers should I use?

All three send the same prompt, built in one place, and parse the reply with the same parser.
They differ only in who carries the text.

| | Needs | What you give up | What you gain |
|---|---|---|---|
| **Any chatbot** (clipboard) | A chatbot window. Any one | Three manual steps; token counts recorded as unknown | Works with every chatbot that exists, including ones with no API. No key, no port, nothing to configure |
| **MCP server** (this page) | An MCP-capable *agent* (Claude Code, Codex, Cursor, Gemini CLI) | A server you have to start and stop, and the security model above to read | No copying by hand; the agent does the whole round trip from one sentence you type |
| **Anthropic API** | A paid API key, pasted each session | Money per call, and a key in QuPath | One click, in-window, and real token counts |

Ollama is a fourth option on the explainer page, for a local model with its own API; it has
never been run either.

## Troubleshooting

> The states below are derived from the design and the code, not from observed failures, so
> the list is neither complete nor confirmed. No client has connected yet.

<details>
<summary><strong>"Connection refused" / the agent cannot see the QP-CAT tools</strong></summary>

**What it usually means:** the server is off -- the most common cause by a distance, because
it is off after every QuPath restart by design, and it stops when the project changes.

**What to do:** three checks, in order.

1. Open **Extensions > QP-CAT > Setup & help** and read the menu item. `(off)` means
   nothing is listening; choose it, press **Start server**, and copy the **new** token.
2. If it reads `(ON - port NNNNN)`, compare that port with the one in your client
   configuration. A fallback port (51517 and up) is the one people overlook.
3. A missing or wrong token gives **401**, not "refused". Refused means nothing is listening
   at that address.

</details>

<details>
<summary><strong>"401" / "Unauthorized"</strong></summary>

**What it usually means:** the token in your client configuration is missing, mistyped, or
from a previous time you started the server -- every start makes a new one.

**What to do:** open the window (choose **Show MCP server window (address and token)** under the tick while the server is on), press **Copy**
beside **Token**, update the configuration, and restart or reload your client so it re-reads
the file. For Claude Code, re-run the `claude mcp add` command after `claude mcp remove
qpcat`.

</details>

<details>
<summary><strong>"403" / "Forbidden"</strong></summary>

**What it usually means:** the request carried an `Origin` or `Host` header that is not
loopback, which is refused on purpose. A command-line agent sends no `Origin` and is
unaffected; a browser-based client, or a web page, is exactly what this check is for.

**What to do:** if a legitimate client of yours is being refused, say which one, and which
version, in a bug report -- do not look for a switch to turn the check off, because there is
not one.

</details>

<details>
<summary><strong>"Could not start the MCP server: ports 51516 to 51525 are all in use"</strong></summary>

**What you see:** a popup titled **QP-CAT MCP Server - Error**:

```
Could not start the MCP server: ports 51516 to 51525 are all in use on this
computer.

Something else is listening there -- most likely another copy of QuPath with
this server already on, or the qupath-mcp extension. Check for a second QuPath
window, stop its MCP server, and try again.

The server was NOT started.
```

**What it usually means:** ten consecutive ports are held, which almost always means several
QuPath windows each with this server on. One other copy of QuPath only moves you to 51517;
the status line then reads `RUNNING since HH:mm on port 51517 (51516 was in use) - ...`.

**What to do:** find the other QuPath window and stop its server, or close it. To see what
holds a port: `ss -ltnp` (Linux), `lsof -i :51516` (macOS), `netstat -ano | findstr 51516`
(Windows). There is no port setting to change; the fallback scan is the port chooser.

</details>

<details>
<summary><strong>"No project is open, so there are no saved clustering results to serve"</strong></summary>

**What you see:** a popup titled **QP-CAT MCP Server - Error**:

```
No project is open, so there are no saved clustering results to serve.

Open a project (File > Open Project), then turn the MCP server on again.

The server was NOT started.
```

**What to do:** what it says. The menu item is always enabled, even with no project, so that
the off-switch can never be unreachable; this popup is the price of that.

</details>

<details>
<summary><strong>"QP-CAT's Python environment is not running in this QuPath session"</strong></summary>

**What the agent is told**, from `qpcat_explain_prompt`:

```
QP-CAT's Python environment is not running in this QuPath session, so the
prompt cannot be built. Nothing has changed.

Ask the person at QuPath to do one of these, then call this tool again:
  - Extensions > QP-CAT > Setup & help > Set up analysis environment
    (first run) -- needed once per machine, and it can take several minutes.
  - Or simply run any QP-CAT clustering, which starts it.

This tool will not start the environment itself: building it can take minutes
and that is not something a network request should be able to trigger.
```

**What it usually means:** QuPath was launched recently and nothing has started QP-CAT's Python
side yet. This is the normal state of a fresh session, not a fault.

**What to do:** run any clustering, or **Set up analysis environment (first run)...** if the
environment has never been built, then ask the agent again. No popup appears in QuPath for
this; the agent relays the message.

</details>

<details>
<summary><strong>"QP-CAT is busy running another analysis"</strong></summary>

**What the agent is told**, as the tool result:

```
QP-CAT is busy running another analysis and can only run one Python task at a
time, so the prompt cannot be built right now. Nothing has changed.

Try again when it finishes. A clustering run on a large image can take many
minutes. The person at QuPath can see progress in the QP-CAT progress window.
```

**What it usually means:** a clustering run holds QP-CAT's Python lock. The tool refuses
rather than waiting, because an agent waiting forty minutes on one call looks like a hang.

**What to do:** wait for the run to finish and ask again.

</details>

<details>
<summary><strong>"No saved clustering result named ..."</strong></summary>

**What the agent is told**, as the tool result:

```
No saved clustering result named "leiden_v2" in the open project.

Call qpcat_list_results to see the names that exist, and use one of those
exactly -- names are matched exactly, not by prefix.
```

**What it usually means:** the agent guessed, abbreviated, or reused a name from a different
project. Names are matched by exact membership in the list the agent was given, never by
prefix and never as a path. The refusal deliberately echoes no filesystem path.

**What to do:** ask the agent to list the results first and pick from the list.

</details>

<details>
<summary><strong>"Too many tool calls in the last minute"</strong></summary>

**What the agent is told**, as the tool result:

```
Too many tool calls in the last minute (limit 30). Nothing has changed. Wait a
moment and try again.
```

A second refusal with the same shape, when the agent fires a call while another is still
running:

```
Another tool call is still running; this server handles one at a time. Nothing
has changed. Wait for it to finish and try again.
```

**What it usually means:** an agent in a loop, or one that issues calls in parallel. Three
calls per result, one after another, is the normal budget; thirty in a minute is not something
a person asking one question produces.

**What to do:** stop the agent, read its transcript, and ask again.

</details>

<details>
<summary><strong>"The clustering result ... already has N suggestions"</strong></summary>

**What the agent is told**, as the tool result:

```
The clustering result "leiden_res1.0_2026-10-11" already has 7 suggestions, written by claude.ai via CHATBOT at 2026-10-11T17:02:40Z. Nothing has changed.

Check with the person at QuPath before replacing them. To replace, call this tool again with overwrite: true; the previous suggestions stay in the LLM audit log beside the result.
```

**What it usually means:** `qpcat_set_explanations` was called on a result that already has
explanations, without `overwrite: true`. The source reads `<agent or model> via <PROVIDER> at
<timestamp>`.

**What to do:** decide. If you want them replaced, tell the agent to call again with
`overwrite: true`; the earlier explanations stay in the audit log, and the success text then
begins `Saved 7 suggestions to the clustering result "...", REPLACING the 7 suggestions that
were already stored there`. If you did not mean to touch that result, the refusal has cost you
nothing -- it changed nothing.

</details>

<details>
<summary><strong>"The reply could not be parsed as the JSON array the prompt asks for"</strong></summary>

**What the agent is told**, as the tool result:

```
The reply could not be parsed as the JSON array the prompt asks for (one object per cluster with cluster_id, phenotype, confidence, rationale, supporting_markers). Nothing has changed. Send the JSON array itself.
```

**What it usually means:** the agent answered in prose, or wrapped the array in so much chat
that no cluster row survived parsing. The same reply pasted into the tab would show "LLM did
not return a row" for every cluster; over MCP it is refused instead, so a result never ends up
holding seven empty rows. The attempt is still written to the audit log.

**What to do:** ask the agent to send only the JSON array. Nothing was stored.

</details>

<details>
<summary><strong>The agent says it is done, but the table has not changed</strong></summary>

**What it usually means:** the explanations were written to the saved result, and what you
are looking at is a window that is not showing that saved result -- most often the live
results window of a run you have not reopened from disk, or no results window at all. The tool
result tells the agent this, so the agent should have said so.

**What to do:** reopen the result from **Extensions > QP-CAT > Results & populations > View
Past Results...** and open the **Cluster Explainer (LLM) [Experimental]** tab. To confirm the
explanations were written rather than lost, check the audit log for an `=== LLM EXPLAIN ===`
entry with provider `MCP` at the time the agent answered.

</details>

<details>
<summary><strong>The server stopped by itself</strong></summary>

**What you see:** the menu item reads `(off)` again; if the window is open its status line
reads `STOPPED - the project changed. Turn it on again from Setup & help to serve the new
project.`; and a notification appears:

```
MCP server stopped: the project changed. The tools only serve the open
project, so it does not follow you across projects. Turn it on again from
Extensions > QP-CAT > Setup & help, and paste the new token into your agent.
```

**What it usually means:** one of two things, and the QuPath log line says which.
`MCP endpoint stopped (reason: project changed)`: you opened or closed a project. The three
tools are scoped to one project, and a project switch must not quietly repoint your agent at a
different project's results, so the server stops instead. `MCP endpoint stopped (reason: idle
for 60 minutes)`: nothing connected and no tool was called for an hour, so the server you
forgot about turned itself off. The window's status line reads `stopped: idle for 60 minutes`
in that case. There is no setting for the hour.

**What to do:** start it again -- and note that you get a **new token**, so your client
configuration needs updating.

</details>

<details>
<summary><strong>Reporting a bug</strong></summary>

**Extensions > QP-CAT > Setup & help > Report a Bug...** Say which client and which version
of it you used, and what the client printed; client compatibility is the unverified part of
this feature, so that is the detail that lets us reproduce it. The report includes QuPath's
log, which contains the server's start, stop, and per-call lines but never the token, the
prompt, or the reply. **Never paste a token into a bug report**; reports land in public GitHub
issues. A token is dead the moment you stop the server, so if one has leaked, stop and start.

</details>

## For maintainers: the wire protocol

<details>
<summary><strong>What the server answers, and why it does not stream</strong></summary>

This is the contract a client sees. Everything here is the design as specified; the Phase 2
tests are what make it true.

**Transport.** A raw `java.net.ServerSocket` bound to the loopback address, one acceptor
thread and a bounded pool of four handler threads, all daemon. Not `com.sun.net.httpserver`:
the official QuPath 0.7.0 Windows build ships a jlinked runtime of 24 modules and
`jdk.httpserver` is not one of them, so anything importing it would fail to load on the
runtime users actually have. The package depends on `java.base` and Gson (host-provided)
only.

**HTTP subset.** One request per connection; every response carries `Connection: close` and
`Cache-Control: no-store`. Ten-second socket timeout; a timeout anywhere is `408` and close. A
header block over 16 KB is `431`; a folded header line, a duplicate `Content-Length`, `Host`,
`Authorization` or `Transfer-Encoding`, or a non-numeric `Content-Length` is `400`. The pool
is four threads with a queue of eight; a request that finds both full gets `503`. The
authentication and `Origin`/`Host` checks run on the headers **before** the body is read, in
the order of the table.

| Request | Response | Meaning |
|---|---|---|
| `POST /mcp` with `Content-Length`, loopback `Origin`/`Host`, valid token | `200` with `Content-Type: application/json`, or `202` with no body for a notification | Normal |
| No `Content-Length`, or `Transfer-Encoding: chunked` | `411 Length Required` | Only `Content-Length` bodies are read; the parser does not dechunk |
| `Content-Length` over 1 MB | `413 Content Too Large` | Caps what `response` can push into the JVM |
| `Expect: 100-continue` | `417 Expectation Failed` | Not supported; send the body |
| `GET /mcp` or `DELETE /mcp` | `405 Method Not Allowed` with `Allow: POST` | The specification lets a server that offers no SSE stream answer GET with 405, and lets it decline DELETE the same way; sessions end when the server stops |
| Any other method or path | `404 Not Found` | There is one endpoint |
| `Host` absent or not loopback (`127.0.0.1`, `localhost`, `[::1]`, optional port); `Origin` present and not `http(s)://` + a loopback authority | `403 Forbidden` | The specification's MUST against DNS rebinding; the rejected origin is logged, the credential never is |
| `Authorization: Bearer <token>` missing or wrong | `401 Unauthorized`, JSON-RPC-shaped body | Compared in constant time with `MessageDigest.isEqual` |
| Request after `initialize` without `Mcp-Session-Id`, or with one the server does not know | `400 Bad Request` | The server assigns a session id on `initialize` and requires it afterwards; stopping the server forgets every session |
| `MCP-Protocol-Version` present but not `2025-06-18`, `2025-03-26` or `2024-11-05` | `400 Bad Request` | The specification's rule for an unsupported version. A missing header is accepted, as the specification asks |

**JSON-RPC subset.** `initialize` (returns `protocolVersion` `2025-06-18`,
`capabilities.tools.listChanged: false`, `serverInfo` with name `qpcat`, `instructions`, and
sets a random `Mcp-Session-Id` on the response; at most 16 live sessions, oldest evicted),
`notifications/initialized` (202, empty), `ping`, `tools/list` (three tools, no `nextCursor`),
`tools/call`. Protocol errors use the standard codes: parse `-32700`, invalid request
`-32600`, unknown method `-32601`, invalid params `-32602`, including an unknown tool name.
They travel in a `200` response as a JSON-RPC error object, as the specification lays out;
only transport-level refusals use the HTTP status codes above. JSON-RPC batch arrays are
refused as invalid requests.

**Why plain JSON.** The transports page of the 2025-06-18 specification, "Sending Messages to
the Server", item 5: for a JSON-RPC request the server MUST return either
`Content-Type: text/event-stream` to start an SSE stream or `Content-Type: application/json`
to return one JSON object, and the client MUST support both. Plain JSON is one of the two
sanctioned behaviors and client support for it is mandatory. This server never streams, so it
never needs a long-lived connection, which is why `Connection: close` is safe.

**Tool failures are results, not errors.** A tool that cannot do its job -- unknown result
name, environment not running, QP-CAT busy, rate limit, existing explanations without
`overwrite` -- returns `result.isError: true` with a text content item, never a JSON-RPC
error. The tools page of the specification draws that line (protocol errors for unknown
tools and invalid arguments, `isError` for business-logic failures), and the practical reason
is that the agent reads an `isError` text and relays it, while many clients swallow a
JSON-RPC error. Every `isError` text on this page is written for a reader who is not looking
at QuPath.

**Rate limiting.** A sliding window of 30 tool calls per minute and a one-in-flight gate on
`tools/call`; excess calls get `isError`, not a queue. A tool's Python round trip is capped at
five minutes of wall clock, after which the call returns `isError` and the Python task is
canceled. The one-in-flight refusal reads
`Another tool call is still running; this server handles one at a time. Nothing has changed.
Wait for it to finish and try again.`

**What is logged.** Start: the URL. Stop: the reason. Per call: tool name, result name,
outcome, duration. Never: the token, the `Authorization` header, the prompt, the reply. The
prompt and reply go through `LlmAuditScrubber` into the project audit log, as for every
provider, and are deliberately not duplicated into the QuPath log, because that log is what
the bug reporter captures.

**Testing a client by hand.** `qupath-mcp`'s standalone Python client speaks exactly this
subset (initialize, initialized, tools/list, tools/call, plain JSON, `Mcp-Session-Id`) and is
the quickest way to drive the server without an agent in the loop. It sends no
`MCP-Protocol-Version` header, which this server accepts.

</details>

## See also

- [LLM cluster explainer](llm-explainer.md) -- the tab itself, the other providers, what the
  LLM sees, and how to cite a run
- [Troubleshooting](troubleshooting.md) -- everything else that can go wrong in QP-CAT
- [Model Context Protocol](https://modelcontextprotocol.io) -- the specification;
  [transports, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
  is the page this server is built against
- [`qupath-mcp`](https://github.com/earthmover-support/qupath-mcp) -- the general-purpose
  QuPath MCP server, with Groovy execution; its
  [security page](https://earthmover-support.github.io/qupath-mcp/security/) is the threat
  model this page is written against
- [`fiji_mcp`](https://github.com/NicoKiaru/fiji_mcp) -- the Fiji precedent for the
  agent-drives-the-imaging-app shape
