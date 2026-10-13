# MCP server for the Cluster Explainer [Experimental]

Let an AI agent you already use -- Claude Code, Codex, Cursor, Gemini CLI -- read a QP-CAT
clustering result and write cluster names back, without an API key in QuPath and without
copying anything by hand.

**For research use only.** A suggested phenotype is a hint to check against the markers, not
a diagnosis. It has not been validated for, and must not be used in, diagnostic or clinical
decision-making.

> **What has actually been run.** On 2026-10-13, on Linux: Claude Code registered the server
> with the one-liner below and `claude mcp list` reported it `Connected`; separately, a Python
> MCP client drove all three tools against a real project from a hidden QuPath (the harness
> scenario), and the Explainer tab updated live during that client's write while a saved
> result was open. A second Linux walk the same day, on a real QuPath window, went from the
> menu tick to a filled tab with the three tools called from that Python client by hand. **No
> agent has yet run the three calls on its own from one typed sentence.** **No other client
> has connected yet** -- Codex, Cursor, Gemini CLI, VS Code and Claude Desktop are written
> from each client's published format and have not been exercised, on any platform, so if you
> are the first to try one, a failure is as likely to be ours as yours -- please
> [report](troubleshooting.md#reporting-a-bug) it. **Not run either:** the jpackaged QuPath
> runtime that the official Windows and macOS builds ship -- only its module list has been
> read, which is what chose the transport (see
> [For maintainers](#for-maintainers-the-wire-protocol)) -- and the 60-minute idle stop,
> which has never fired.
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

Six words this page and the window use, each in one line:

- **Bearer token**, sent as `Authorization: Bearer <TOKEN>` -- a one-time password the window
  shows you, carried in a standard HTTP header. "Bearer" means whoever holds it is let in,
  which is why it lives for one server session and dies at **Stop server**.
- **Loopback**, `127.0.0.1` -- the address that means "this computer only". Nothing on your
  network, wired or Wi-Fi, can connect to it.
- **Socket** -- the open network port the server listens on. "Open the socket" means start
  listening; "close the socket" means stop.
- **`Origin` header** -- what a browser attaches to a request to say which web site sent it.
  The server refuses any request carrying one that is not loopback, so a page you visit
  cannot drive your QuPath.
- **Event stream** (SSE) -- one of the two ways an MCP server may answer over HTTP. This one
  answers with a single plain JSON object instead, which every MCP client must accept.
- **`-s user`** -- a Claude Code flag: register the server for every folder you open Claude
  Code in, not only the current one.

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
not planned for this release. On a managed install an administrator can go one step further
and remove the feature with a launcher option, `-Dqpcat.mcp.disabled=true`, which hides both
menu items; see [For IT administrators](#for-it-administrators) below.

### Where it listens, and who it answers

| Property | What QP-CAT does | Why |
|---|---|---|
| Address | Binds `127.0.0.1` (loopback) only, never `0.0.0.0`. Port **51516**; if that is taken it tries 51517 through 51525 and tells you which one it bound | Nothing outside this machine can reach it, including other machines on your lab network or VPN. 51516 is not `qupath-mcp`'s 51515, so both can run |
| `Origin` and `Host` headers | A request whose `Host` is missing or not loopback, or whose `Origin` is present and not loopback, is refused with **403** | A loopback bind alone does not stop a web page you visit from posting to the port from your browser. The MCP specification makes the `Origin` check a MUST ([transports, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)) |
| Token | A fresh random token is generated every time you start the server, shown in the window for as long as that server is running, and required on every request as `Authorization: Bearer <TOKEN>`. A request without it is refused with **401** | A stale config file cannot stay a credential: QP-CAT never persists the token or writes it to a log, and it changes on every start. Your agent's own configuration does hold a copy -- see [Residual risk](#residual-risk-stated-plainly) |
| Lifetime | The server stops by itself when the project changes or closes, and after 60 minutes with no authenticated request or tool call -- a port scan or a client with a stale token does not keep it alive. QuPath exiting ends it too, since nothing remembers it was on | The tools are scoped to one project; switching projects must not silently repoint your agent at a different one. The idle stop covers the server you forgot about |
| Rate | At most 120 tool calls per minute, not counting `qpcat_list_results`, and one `tools/call` in flight at a time; the rest are refused with a message that says how long to wait, not queued | The specification requires rate limiting. A serial agent makes three calls per result, so 120 catches a loop without stalling a cohort; one-at-a-time matches QP-CAT's own one-Python-task-at-a-time rule |

### The three tools, and exactly what each one can do

| Tool | Arguments | Reads | Writes |
|---|---|---|---|
| `qpcat_list_results` | none | The clustering results saved in the open project, as a JSON array, newest first by each result's own timestamp: name, timestamp, one-line summary (algorithm, number of clusters and of cells), a coarse scope (`1 image`, `8 images`, `3 areas` -- never an image or project name), size in bytes, what it was derived from, and whether it already has explanations | Nothing |
| `qpcat_explain_prompt` | `result` (a name from the list above), optional `top_n`, optional `cluster_ids` | One saved result's per-cluster marker rankings; returns the explainer prompt QP-CAT would send to any other provider -- cluster ids, and for each cluster its top-N marker names with their Wilcoxon score and log2 fold change, nothing else -- then a short metadata block with the prompt's hash, `top_n` and `cluster_ids` | Nothing |
| `qpcat_set_explanations` | `result`, `response` (the agent's reply text), `agent` (a label for the record), optional `overwrite`, and the provenance fields from the metadata block: `prompt_hash`, plus `top_n` and `cluster_ids` when they were not the defaults | The agent's reply; rebuilds the prompt from those fields and refuses if its hash does not match `prompt_hash` | The cluster explanations, into that saved result and into the project audit log, with the prompt actually answered |

Those marker rankings are the same statistics every other provider sees, built by the same
code (`build_prompt` in `run_llm_explainer.py`): for each cluster, its id and the top-N
markers by Wilcoxon score, each as a marker name, its score and its log2 fold change. No cell
counts, no expression table, no p-values. See
[What the LLM sees](llm-explainer.md#what-the-llm-sees) -- that section is the authority and
this page does not restate it.

**What text does leave QuPath**, across the three tools, is therefore: the names of your
saved results (which you chose -- a result you named after a case carries that name out),
their timestamps, cluster and cell counts, a coarse scope, cluster ids, marker names with
their rank scores, and -- in a refusal -- the label and date of explanations already stored.
A derived result's description can also carry a cluster name you typed when you
sub-clustered. Everything the agent receives it may forward to its own LLM provider; QuPath
itself makes no outbound connection.

If a result already has explanations, `qpcat_set_explanations` refuses unless the agent passes
`overwrite: true`, and its refusal names who wrote the existing ones and when. A careful agent
never hits this, because `qpcat_list_results` already says which results have explanations.
Either way the project audit log keeps the record a PI needs: a refused write is recorded as
a `=== LLM EXPLAIN [REFUSED] ===` block naming the reason, and an accepted overwrite adds an
`Overwrite: replaced <provider>/<label> written <timestamp> (N suggestions)` row to its entry. The previous
explanations' own entry stays in the log.

### What it cannot do

- **No file paths.** No tool takes a path, a URL, or a filename. The only way to name a result
  is to pick one of the names `qpcat_list_results` returned, matched exactly; a name that is
  not on that list is refused without ever being resolved as a path, and the refusal never
  echoes one.
- **No code.** Nothing runs Groovy, a script, or a command. This is the one deliberate
  difference from `qupath-mcp`, whose
  [security page](https://earthmover-support.github.io/qupath-mcp/security/) states plainly
  that its tools can run any code in QuPath with your privileges and file access.
- **No pixels, no images, no cells.** No tool reads an image, a tile, a thumbnail, or an
  individual cell measurement. No image or file name leaves either: a result's scope is
  reported as `1 image` or `8 images`, never as the image's name, and the prompt carries
  marker names only.
- **No deleting, no renaming, no reclustering.** The single write is the explanation text on
  one saved result.
- **No environment build.** If QP-CAT's analysis environment is not up,
  `qpcat_explain_prompt` and `qpcat_set_explanations` say so and stop (once built, the
  environment normally starts in the background when QuPath launches). A network request
  cannot trigger a minutes-long install.

**Once connected, the agent acts on your behalf -- within those three tools, and nowhere
else.** It can list your saved results, read one result's marker statistics, and write cluster
explanations into it. That is the entire surface.

### Residual risk, stated plainly

With loopback, the `Origin` check, and the token in place, the remaining exposure is another
program running as you, on this machine, that can read the token. QP-CAT writes the token
nowhere -- not to QuPath's log, not to a preference, not to a file -- but the feature only
works once you have put it somewhere: your agent's configuration holds it in plain text
(`claude mcp add` writes it into Claude Code's config; the JSON and TOML snippets below embed
it), and the pasted command lands in your shell history. Another process running as you does
not need QuPath's memory; it needs to read a dotfile. That is why the token dies the moment
you press **Stop server**, why you re-run the add command after every **Start server**, and
why `claude mcp remove qpcat` (or deleting the entry) belongs in your routine when you are
done. A program that can read your dotfiles could read your project files directly anyway,
which is where the risk bottoms out; that is a sentence rather than a claim of isolation. Two
things worth acting on: do not turn the server on a machine you share with people you would
not hand your project to, and turn it off when you are done. Whether a clinical or managed
QuPath may run it at all is a policy question for whoever manages that machine; the switch
they can pull is in the next block.

### For IT administrators

<details>
<summary><strong>The facts a security review needs, in one place</strong></summary>

| | |
|---|---|
| Listener | TCP on `127.0.0.1` only (a Java `ServerSocket` bound to the loopback address; never `0.0.0.0`). Port 51516, falling through 51517 to 51525 if taken. Plain HTTP, no TLS: the traffic never leaves the loopback interface. One endpoint, `POST /mcp` |
| Authentication | A per-start bearer token: 32 random bytes from `SecureRandom`, base64url, compared in constant time. Required on every request; a missing or stale one is `401` before the body is read |
| Request checks | `Host` must be present and loopback; `Origin`, if present, must be loopback; anything else is `403`. Body capped at 1 MB (`413`). No chunked bodies (`411`) |
| Outbound connections | None. QuPath opens no client connection for this feature. The **agent** is the online component: whatever the tools return to it, the agent may forward to its own LLM provider under that vendor's data handling, not ours |
| Where the token ends up | In QP-CAT: memory only, cleared at Stop; never logged, never in a preference or a file. On the client side: in plain text in the agent's configuration (Claude Code's config for `claude mcp add`; the JSON/TOML files under [Connecting your agent](#connecting-your-agent)), and in the shell history of whoever pasted the command |
| What is logged, QuPath log (in memory unless the user has set a log file) | One line per start (the URL) and per stop (the reason); one line per tool call with the tool name, the result name and the outcome; a rejected `Host`/`Origin` value, truncated to 80 printable characters; the fact of a rejected token, never the token itself |
| What is logged, project audit log (`<project>/qpcat/logs/qpcat_YYYY-MM-DD.log`) | One `MCP SERVER` event per start (`started on 127.0.0.1 port 51516 (loopback only; the token is not recorded)`) and per stop (`stopped (reason: ...)`); one `=== LLM EXPLAIN ===` block per accepted write with the agent's self-reported label, the client's `User-Agent`, the coarse image scope, the prompt actually answered and the reply verbatim; a `=== LLM EXPLAIN [REFUSED] ===` block per refused write with the reason; an `Overwrite:` row when stored explanations were replaced. Credential-shaped strings are scrubbed; nothing else is -- the scrubber de-identifies nothing |
| Persistence of "on" | None. No preference, no file, no autostart. Every QuPath launch begins with the server off |
| Rate | 120 tool calls per minute (`qpcat_list_results` exempt), one in flight; a Python round trip is abandoned after five minutes |
| To confirm it is off | **Extensions > QP-CAT > Setup & help** reads `MCP server for AI agents (off)`, unticked, and nothing listens on 51516-51525: `ss -ltn \| grep 5151` (Linux), `lsof -nP -iTCP:51516-51525 -sTCP:LISTEN` (macOS), `netstat -ano \| findstr 5151` (Windows) print nothing |
| To disable it for every user of a managed install | Add `-Dqpcat.mcp.disabled=true` to the launcher's Java options. For the official jpackaged QuPath that is the `[JavaOptions]` section of the `.cfg` beside the application -- on Windows `QuPath-0.7.0\app\QuPath-0.7.0.cfg` (and its `(console)` twin), on macOS the same file under `Contents/app/` inside the `.app` bundle -- as one more line, `java-options=-Dqpcat.mcp.disabled=true`. With the property set, neither menu item is built, QuPath's log carries `MCP server disabled by policy (qpcat.mcp.disabled=true)` at startup, and a start attempt (including one from a script) refuses with `The MCP server is disabled on this installation (qpcat.mcp.disabled=true).` Users of a managed install cannot edit that file. It is not a preference, so it adds no way to persist "on" |

</details>

## Turning it on and off

Turning it on is two steps; turning it off is one.

1. Choose **Extensions > QP-CAT > Setup & help > MCP server for AI agents (off)**. It is the
   second-to-last item in that submenu, above a grayed **Show MCP server window (address and
   token)**. A window titled **QP-CAT MCP Server** opens. **The server is not running yet.**
   The window leads with an amber banner saying what you are about to start, and a block
   headed CAN / CANNOT / REACH / LIMITS / LOGS that restates the [Security](#security)
   section. As shipped it reads:

   ```
   CAN:    list the saved clustering results in the open project; build the
           explainer prompt from one result's marker table; write suggested
           cluster names and rationales into that saved result and the audit
           log (it refuses to replace existing suggestions unless the agent
           asks to, and the old ones stay in the audit log).
   CANNOT: read or write any other file, run a script, change your images,
           delete a result, or reach anything outside this project.
   REACH:  programs running on this computer as you. A web page you visit is
           refused (we check the Origin header), and nothing on your network can
           connect (we listen on 127.0.0.1 only). A different program on this
           machine, running as you, could use the token if it could read it --
           but such a program could read your project directly anyway.
   LIMITS: 120 tool calls a minute (listing results is not counted), one at a
           time; a refusal says how long to wait. It stops by itself after 60
           minutes with no tool calls, and whenever the project changes.
   LOGS:   each tool call -- tool, result name, outcome -- to the QuPath
           log; the prompt and reply to the project audit log; never the
           token.
   ```

   The amber banner above it ends with the same sentence as this page's top: `Research use
   only: these are suggestions to check, not diagnoses.` A project must be open; with none
   open you get an error instead, and nothing starts.
2. Press **Start server**. The status line changes to `RUNNING since HH:mm - 0 tool calls so
   far`, the **Address** field shows `http://127.0.0.1:51516/mcp` (or the fallback port, named
   in the status line), and the **Token** field shows the token. Each has a **Copy** button.
   The menu item now reads **MCP server for AI agents (ON - port 51516)** with a check mark,
   and the QuPath log gains a line, `MCP endpoint listening on http://127.0.0.1:51516/mcp`
   (two more if the default port was taken: `MCP port 51516 is in use; trying the next one`
   and `MCP port 51516 was in use; bound 51517 instead`). The project audit log gains a start
   line too, with the port. The token is in neither.
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
new one. QP-CAT writes the token nowhere -- not QuPath's log, not a preference, not a file --
so inside QuPath the window is the only place it appears. Outside QuPath it lives wherever
you put it: your agent's configuration and your shell history, until you remove the
registration (see [Residual risk](#residual-risk-stated-plainly)).

The tool-call counter in the status line is the ambient reassurance. If it still reads
`0 tool calls so far` after an hour, nothing has used the server.

When the server stops for a reason you did not ask for, the window's status line says why,
the menu item returns to `(off)`, and the log line `MCP endpoint stopped (reason: ...)` names
the reason: `user turned it off`, `project changed`, or `idle for 60 minutes`; the project
audit log gets a matching stop line. The idle clock restarts only on a request that presented
the right token (a connection with no token or a stale one does not count), and is checked
once a minute. A project
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

**Ran this before? Remove the old entry first.** The token changes every time you start the
server, and `claude mcp add` refuses to replace an entry that already exists:

```
MCP server qpcat already exists in local config
```

That line (exit code 1) means the previous token is still registered, and the next
`claude mcp list` will show a 401. So after every **Start server** the routine is
`claude mcp remove qpcat`, then the command above. The window's pane says the same under
its command: `If Claude Code says the server already exists, run: claude mcp remove qpcat   then the command above.`

The window's **Connect your agent > Claude Code** pane shows the command with the real port
and token filled in, and a **Copy command** button. Run it in a terminal, in the folder where
you will start Claude Code -- the default scope is `local`, meaning that folder only -- or
add `-s user` to the command to register the server for every folder. Then check from the
terminal with `claude mcp list`, which prints `qpcat: http://127.0.0.1:<PORT>/mcp (HTTP) -
Connected` (or the 401 reason), or type `/mcp` inside Claude Code and look for `qpcat` with
three tools.

The copied command uses backslash line continuations, which PowerShell and `cmd` do not read;
on Windows, join it onto one line before running it. Untested there, like the rest of the
Windows path.

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

What happens, in order. The outputs below are illustrative -- the field names and the shape
are the code's, the values are made up, and no agent has yet run this sequence unprompted;
the three calls have so far been made by hand.

1. **The agent calls `qpcat_list_results`.** It gets back the saved results in the open
   project as one JSON array, one object per result, newest first by each result's own
   timestamp -- not by file date, so a project you copied from a share or restored from a
   backup still lists the latest run first (values illustrative; the field names are the
   code's):

   ```json
   [
     {"name": "leiden_res1.0_2026-10-11", "timestamp": "2026-10-11T16:42:08",
      "summary": "leiden, 7 clusters, 48,210 cells", "scope": "1 image",
      "autoSaved": false, "sizeBytes": 31457280, "hasExplanations": false},
     {"name": "kmeans_k8_2026-10-09", "timestamp": "2026-10-09T11:05:51",
      "summary": "kmeans, 8 clusters, 48,210 cells", "scope": "1 image",
      "autoSaved": true, "sizeBytes": 29360128, "hasExplanations": true}
   ]
   ```

   `scope` is deliberately coarse -- `1 image`, `8 images`, `3 areas` -- and never the
   image's name.

2. **The agent calls `qpcat_explain_prompt`** with `result: "leiden_res1.0_2026-10-11"`. It
   gets back two text blocks. First the prompt QP-CAT would have sent to any other provider,
   verbatim -- the marker table and the instructions, including the rule that no p-values are
   sent and no significance language is wanted. Then a metadata block:

   ```
   prompt_hash: <hash>
   prompt_template: cluster_phenotype_v2
   result: leiden_res1.0_2026-10-11
   top_n: 10
   cluster_ids: all
   Next step: answer the prompt above in the exact JSON shape it asks for, then call qpcat_set_explanations with: result (this name), response (your full reply), agent (a short label for the audit log), prompt_hash (the value above), and the same top_n and cluster_ids you passed here, if any. QP-CAT rebuilds the prompt from those and refuses the write if its hash differs from prompt_hash, so the record it stores is the prompt you answered.
   ```

3. **The agent answers it.** This is the part your subscription pays for; QuPath is not
   involved, and no call leaves your machine from QuPath. The agent produces the JSON array of
   per-cluster suggestions the prompt asks for.

4. **The agent calls `qpcat_set_explanations`** with that reply, a label such as
   `agent: "Claude Code (claude-fable-5-1)"`, and the `prompt_hash` from step 2 (plus `top_n`
   and `cluster_ids` if it changed them). QP-CAT rebuilds the prompt from those fields and
   refuses if the hash does not match, so the audit log can never record a prompt the agent
   did not see. It then parses the reply with the same parser the clipboard and API paths
   use, writes the explanations into the saved result, and writes the prompt and reply to the
   project audit log. The tool result tells the agent what happened, including whether
   anything on screen changed:

   ```
   Saved 7 suggestions (7 with a phenotype, 0 the model declined or did not answer) to the clustering result "leiden_res1.0_2026-10-11".

   A results window showing this result is open in QuPath; its "Cluster Explainer (LLM) [Experimental]" tab has been updated.
   ```

   The first number is the clusters in the result; the bracket says how many received a
   phenotype and how many did not. A cluster the agent skipped, or answered with `phenotype:
   null`, gets an "LLM did not return a row" or "(no suggestion)" line in the table rather
   than an error, exactly as on the other providers, and the text adds `Clusters left without
   a phenotype: [2, 5].` A row whose `cluster_id` is not in the result is dropped and named:
   `Rows for cluster ids [100, 101] matched no cluster in this result and were dropped.` A
   reply with no usable row at all is refused and nothing is stored; see
   [Troubleshooting](#troubleshooting).

Where you see the result:

- **The Cluster Explainer tab**, if that result is open in a results window: a bold line
  pinned to the top of the tab, in view at any window size and scroll position, reads `An AI
  agent just wrote 7 suggestions over MCP. They are saved with this result; review them in
  the Suggestions table below.`; the tab scrolls so the **Suggestions** heading is at the top;
  the table repopulates; and the line under the heading reads `Last written by Claude Code
  (claude-fable-5-1) over MCP at 14:07 -- by an AI agent, not from this panel. Saved to the
  result on disk.` No popup, no toast. If no results window shows that result, the tool
  result says so instead and names the menu path, and you open the result from **View Past
  Results...** to see them; the line then reads `Loaded from the saved result: written by
  Claude Code (claude-fable-5-1) via MCP at 14:07.`
- **The audit log**, at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log`, under the
  `=== LLM EXPLAIN ===` entry: provider `MCP`; `Agent:`, the label the agent chose to send,
  recorded as sent and **not verified** by QP-CAT -- check it against the agent's own
  transcript; `Client:`, the agent's `User-Agent` header, which real clients set and which is
  harder to misreport by accident than a label the model composes; `Image:`, the coarse scope
  (`1 image`, `8 images`); the prompt hash, `top_n` and cluster ids as actually answered; and
  the prompt and the reply verbatim; a `Dropped rows:` row when the reply named cluster ids
  the result does not have. An overwrite adds an `Overwrite: replaced <provider>/<label>
  written <timestamp> (N suggestions)` row; a refused write is its own `=== LLM EXPLAIN [REFUSED] ===` block
  with a `Refused:` row giving the reason and the summary `REFUSED, nothing written`. `User:`
  is the account QuPath runs as, not necessarily the person at the
  keyboard. The entry's header timestamp is local wall-clock time with no zone offset
  (`yyyy-MM-dd HH:mm:ss`); the timestamp saved inside the result's bundle is ISO-8601 with
  the offset. This is the record every other provider writes, plus the two MCP rows, and it
  is what lets a methods section say what answered. See
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

> The states below are derived from the code and from one Linux walk that provoked the
> wrong-name, overwrite, prose-reply and stale-token refusals on purpose; the rest have not
> been observed, so the list is neither complete nor confirmed.

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

**What the terminal shows:** Claude Code prints the server's reply verbatim,
`Unauthorized: missing or invalid bearer token.`, and `claude mcp list` reads
`Failed to connect -- Server rejected the configured Authorization header (HTTP 401)`.

**What to do:** open the window (choose **Show MCP server window (address and token)** under
the tick while the server is on), press **Copy** beside **Token**, update the configuration,
and restart or reload your client so it re-reads the file. For Claude Code: first
`claude mcp remove qpcat`, then the `claude mcp add` command -- in that order, because `add`
refuses while the old entry exists.

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

**What it usually means:** QP-CAT's analysis environment has not been built on this machine
yet, or its background start at QuPath launch failed. Once built, the environment starts in
the background when QuPath launches, so on a working install `qpcat_explain_prompt` succeeds
within a minute or two of launch without any clustering run; if it refuses, look for
`ApposeClusteringService` in the QuPath log.

**What to do:** **Set up analysis environment (first run)...** if the environment has never
been built, or run any clustering to start it, then ask the agent again. No popup appears in
QuPath for this; the agent relays the message.

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
Too many tool calls in the last minute (limit 120 per minute). Nothing has
changed. Retry after 23 s.
```

A second refusal with the same shape, when the agent fires a call while another is still
running:

```
Another tool call is still running; this server handles one at a time. Nothing
has changed. Wait for it to finish and try again.
```

**What it usually means:** an agent in a loop, or one that issues calls in parallel. Three
calls per result, one after another, is the normal budget, so a forty-result cohort fits in a
minute; a hundred and twenty is not something a person asking one question produces.
`qpcat_list_results` is not counted, so an agent that re-lists on every turn is not penalized
for it. The refusal says how long to wait, and argument mistakes -- an unknown result name,
an unknown argument, a wrong type -- are reported as such even while the limit is in force.

**What to do:** stop the agent, read its transcript, and ask again.

</details>

<details>
<summary><strong>Argument refusals: unknown names, a string for <code>overwrite</code>, a bad <code>prompt_hash</code>, cluster ids the result lacks</strong></summary>

**What the agent is told**, as the tool result, one of:

```
qpcat_explain_prompt does not accept the argument(s) [topN]. Accepted argument names: [cluster_ids, result, top_n]. Nothing has changed.
```
```
overwrite must be the JSON boolean true or false, not the string "true" (the string "true" and the number 1 are refused). Nothing has changed.
```
```
prompt_hash must be the 64-character sha256 hex string from the metadata block of qpcat_explain_prompt, not the string "abc". Nothing has changed.
```
```
cluster_ids [999] are not clusters of the result "leiden_res1.0_2026-10-11"; its cluster ids are [0, 1, 2, 3, 4, 5, 6]. Nothing has changed.
```
```
The prompt_hash you sent (<hash>) does not match the prompt QP-CAT rebuilds for "leiden_res1.0_2026-10-11" with top_n=10 and cluster_ids=all (<hash>). Either you answered a prompt built with different top_n / cluster_ids, or the result changed since. Nothing has changed.

Call qpcat_explain_prompt again with the top_n and cluster_ids you want, answer the prompt it returns, and pass its prompt_hash back here.
```

**What it usually means:** the agent camel-cased an argument, sent a string where a boolean
or an integer was wanted, guessed a cluster id, or answered one prompt and reported another's
hash. Argument checks run before the rate limiter, so the agent always gets the specific
refusal. The `prompt_hash` mismatch and the cluster-id refusal on a write are recorded in the
project audit log as `=== LLM EXPLAIN [REFUSED] ===` blocks; `top_n` must also be a whole
number of at least 1.

**What to do:** read the refusal; each names the accepted form. Nothing was stored.

</details>

<details>
<summary><strong>"QuPath's interface thread did not respond within 15 s"</strong></summary>

**What the agent is told**, as the tool result:

```
QuPath's interface thread did not respond within 15 s; it is busy or blocked (a long operation, or a dialog waiting for the
person at QuPath). Nothing has changed. Try again once QuPath responds.
```

**What it usually means:** QuPath's window is frozen or a modal dialog is waiting for a
click. The tools read the open project on QuPath's interface thread; when it does not answer
they say so rather than reporting "no project is open". **Start server** fails with the same
message in the same state.

**What to do:** look at QuPath, dismiss the dialog or wait for the operation, ask again.

</details>

<details>
<summary><strong>"The clustering result ... already has N suggestions"</strong></summary>

**What the agent is told**, as the tool result:

```
The clustering result "leiden_res1.0_2026-10-11" already has 7 suggestions, written by claude.ai via CHATBOT at 2026-10-11T17:02:40Z. Nothing has changed.

Check with the person at QuPath before replacing them. To replace, call this tool again with overwrite: true (the JSON boolean); the previous suggestions stay in the LLM audit log beside the result.
```

**What it usually means:** `qpcat_set_explanations` was called on a result that already has
explanations, without `overwrite: true`. The source reads `<agent or model> via <PROVIDER> at
<timestamp>`.

**What to do:** decide. If you want them replaced, tell the agent to call again with
`overwrite: true` -- the JSON boolean; the string `"true"` and the number `1` are refused
with a message saying so. The earlier explanations stay in the audit log, the success text
then begins `Saved 7 suggestions (7 with a phenotype, 0 the model declined or did not answer)
to the clustering result "...", REPLACING the 7 suggestions that were already stored there`,
and the new entry in the audit log carries an `Overwrite: replaced ...` row naming what it
replaced. If you did not mean to touch that
result, the refusal has cost you nothing -- it changed nothing, and it is recorded in the
audit log as a `=== LLM EXPLAIN [REFUSED] ===` block so the attempt is on record.

</details>

<details>
<summary><strong>"The reply could not be parsed as the JSON array the prompt asks for"</strong></summary>

**What the agent is told**, as the tool result:

```
The reply could not be parsed as the JSON array the prompt asks for (one object per cluster with cluster_id, phenotype, confidence, rationale, supporting_markers), or none of its rows named a cluster of this result. Nothing has changed. Send the JSON array itself.
```

A second line, `Rows for cluster ids [...] matched no cluster in this result and were
dropped.`, follows when the array was fine but every row named a cluster the result does not
have.

**What it usually means:** the agent answered in prose, wrapped the array in so much chat
that no cluster row survived parsing, or numbered its clusters from a different result. The same reply pasted into the tab would show "LLM did
not return a row" for every cluster; over MCP it is refused instead, so a result never ends up
holding seven empty rows. The refusal is written to the project audit log as an
`=== LLM EXPLAIN [REFUSED] ===` block with the reason in its `Refused:` row, so a PI can see
the attempt.

**What to do:** ask the agent to send only the JSON array. Nothing was stored.

</details>

<details>
<summary><strong>The agent says it is done, but the table has not changed</strong></summary>

**What it usually means:** when a window *is* showing that saved result, a write pins a bold
`An AI agent just wrote N suggestions over MCP ...` line to the top of the Explainer tab and
scrolls the Suggestions table into view, so "nothing changed" means the explanations were
written to the saved result and what you are looking at is a window that is not showing that
saved result -- most often the live results window of a run you have not reopened from disk,
or no results window at all. The tool result tells the agent this, so the agent should have
said so.

**What to do:** reopen the result from **Extensions > QP-CAT > Results & populations > View
Past Results...** and open the **Cluster Explainer (LLM) [Experimental]** tab. To confirm the
explanations were written rather than lost, check the audit log for an `=== LLM EXPLAIN ===`
entry with provider `MCP` at the time the agent answered.

</details>

<details>
<summary><strong>The server stopped by itself</strong></summary>

**What you see:** the menu item reads `(off)` again; if the window is open its status line
reads `STOPPED - the project changed. Press Start server to serve the new project; you will
get a new token.`; and a notification appears:

```
MCP server stopped: the project changed. The tools only serve the open
project, so it does not follow you across projects. To serve the new
project, open Extensions > QP-CAT > Setup & help > MCP server for AI
agents, press Start server, and paste the new token into your agent.
```

**What it usually means:** one of two things, and the QuPath log line says which.
`MCP endpoint stopped (reason: project changed)`: you opened or closed a project. The three
tools are scoped to one project, and a project switch must not quietly repoint your agent at a
different project's results, so the server stops instead. `MCP endpoint stopped (reason: idle
for 60 minutes)`: nothing connected and no tool was called for an hour, so the server you
forgot about turned itself off. The window's status line reads `STOPPED - no tool calls for
60 minutes. Press Start server to turn it on again; you will get a new token.` in that case.
There is no setting for the hour, and this stop has never been observed to fire.

**What to do:** start it again -- and note that you get a **new token**, so your client
configuration needs updating.

</details>

<details>
<summary><strong>Reporting a bug</strong></summary>

**Extensions > QP-CAT > Setup & help > Report a Bug...** Say which client and which version
of it you used, and what the client printed; client compatibility is the unverified part of
this feature, so that is the detail that lets us reproduce it. The report includes QuPath's
log, which contains the server's start, stop, and per-call lines (tool name, result name,
outcome) but never the token, the prompt, or the reply. **Never paste a token into a bug report**; reports land in public GitHub
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
| `Content-Length` over 1 MB | `413 Payload Too Large` | Caps what `response` can push into the JVM |
| `Expect: 100-continue` | `417 Expectation Failed` | Not supported; send the body |
| `GET /mcp` or `DELETE /mcp` | `405 Method Not Allowed` with `Allow: POST` | The specification lets a server that offers no SSE stream answer GET with 405, and lets it decline DELETE the same way; sessions end when the server stops |
| Any other method or path | `404 Not Found` | There is one endpoint |
| `Host` absent or not loopback (`127.0.0.1`, `localhost`, `[::1]`, optional port); `Origin` present and not `http(s)://` + a loopback authority | `403 Forbidden` | The specification's MUST against DNS rebinding; the rejected origin is logged, the credential never is |
| `Authorization: Bearer <token>` missing or wrong | `401 Unauthorized`, JSON-RPC-shaped body | Compared in constant time with `MessageDigest.isEqual` |
| Request after `initialize` without `Mcp-Session-Id`, or with one the server does not know | `400 Bad Request` | The server assigns a session id on `initialize` and requires it afterwards; stopping the server forgets every session |
| `MCP-Protocol-Version` present but not `2025-06-18`, `2025-03-26` or `2024-11-05` | `400 Bad Request` | The specification's rule for an unsupported version. A missing header is accepted, as the specification asks |

**JSON-RPC subset.** `initialize` (echoes the client's `protocolVersion` when it is
`2025-06-18` or `2025-03-26`, otherwise answers `2025-06-18`; returns
`capabilities.tools.listChanged: false`, `serverInfo` with name `qpcat`, `instructions`, and
sets a random `Mcp-Session-Id` on the response; at most 16 live sessions, least recently
used evicted),
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
at QuPath. An unexpected exception inside a tool reaches the agent as its class name only
(`The tool failed with IOException. Nothing has changed. ...`); the message, which can hold a
file path, stays in the QuPath log.

**Rate limiting.** A sliding window of 120 tool calls per minute, in which
`qpcat_list_results` does not count, and a one-in-flight gate on `tools/call`; excess calls
get `isError` with a retry-after figure, not a queue, and argument validation runs first so
a bad result name is reported as such. A tool's Python round trip is capped at five minutes
of wall clock, after which the call returns `isError` and the result is discarded; this is a
soft cancel -- the Python task drains to completion in the background, it is not killed. The
one-in-flight refusal reads
`Another tool call is still running; this server handles one at a time. Nothing has changed.
Wait for it to finish and try again.`

**What is logged.** QuPath log -- start: the URL; stop: the reason; per call: tool name,
result name, outcome, duration (`MCP tool call: qpcat_set_explanations [result=...] -> ok
(140 ms)`). Project audit log (`OperationLogger`) -- one `MCP SERVER` event per start and
stop with the port and reason; per accepted write an `=== LLM EXPLAIN ===` block carrying
`Agent:` (self-reported), `Client:` (the `User-Agent` header), `Image:` (the coarse scope),
the hash, `top_n` and cluster ids actually answered, and an `Overwrite:` row when it replaced
stored explanations; per refused write a `=== LLM EXPLAIN [REFUSED] ===` block with the reason. Never, anywhere:
the token, the `Authorization` header. The prompt and reply go through `LlmAuditScrubber`
into the project audit log, as for every provider, and are deliberately not duplicated into
the QuPath log, because that log is what the bug reporter captures.

**Admin switch.** The system property `qpcat.mcp.disabled=true`
(`McpServerController.DISABLED_PROPERTY`), read once at class load so a script cannot flip it:
neither menu item is built and `start()` throws `IOException` with `The MCP server is disabled
on this installation (qpcat.mcp.disabled=true).` Deliberately not a preference, so the
no-persisted-"on" invariant is untouched.

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
