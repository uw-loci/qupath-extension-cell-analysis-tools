# A stand-in for Appose's python_worker that speaks just enough of the line
# protocol to exercise ApposeTaskRunner without a pixi environment.
#
# Behaviour is chosen by a "#fake: <mode>" line in the task script:
#   complete         LAUNCH, UPDATE, COMPLETION with outputs {ok, pid, echo}
#   die-first        when the process was started with --stale, its first task
#                    fails with "thread death" before any LAUNCH and later
#                    tasks complete; a process started without --stale completes
#                    (a replacement worker therefore succeeds, a reused one not)
#   die-always       every task fails with "thread death" before any LAUNCH
#   update-then-die  LAUNCH, UPDATE, then "thread death" (Python had started)
#   fail             LAUNCH, then FAILURE "boom"
#
# COPIED BETWEEN REPOS together with ApposeTaskRunnerTest; see
# tools/check_appose_copies.py.
import json
import os
import sys

stale = "--stale" in sys.argv[1:]
died = False


def respond(uuid, response_type, **extra):
    message = {"task": uuid, "responseType": response_type}
    message.update(extra)
    print(json.dumps(message), flush=True)


for raw in sys.stdin:
    line = raw.strip()
    if not line:
        break
    request = json.loads(line)
    if request.get("requestType") != "EXECUTE":
        continue
    uuid = request["task"]
    script = request.get("script") or ""
    mode = "complete"
    for script_line in script.splitlines():
        if script_line.startswith("#fake:"):
            mode = script_line[len("#fake:") :].strip()

    if mode == "die-first" and stale and not died:
        died = True
        respond(uuid, "FAILURE", error="thread death")
        continue
    if mode == "die-always":
        respond(uuid, "FAILURE", error="thread death")
        continue

    respond(uuid, "LAUNCH")
    if mode == "update-then-die":
        respond(uuid, "UPDATE", message="started", current=1, maximum=2)
        respond(uuid, "FAILURE", error="thread death")
        continue
    if mode == "fail":
        respond(uuid, "FAILURE", error="boom")
        continue

    respond(uuid, "UPDATE", message="working", current=1, maximum=1)
    respond(
        uuid,
        "COMPLETION",
        outputs={"ok": 1, "pid": os.getpid(), "echo": request.get("inputs") or {}},
    )
