"""The reply parser finds the JSON array however the model wrapped it (H44).

The old fallback took the first ``[`` and counted brackets, inside strings too, so
prose such as "Here is my answer [1]:" before the array, or a rationale holding
"see ref 12]", gave no rows and a false "no JSON array" error. A cluster answered
twice kept the last row silently, and cluster_id 3.7 became 3.
``McpExplainerTools.findReplyArray`` / ``clusterId`` follow the same rule, pinned by
``McpExplainerToolsTest``.
"""

import importlib.util
import json

import pytest

from conftest import SCRIPTS_DIR


@pytest.fixture()
def mod():
    spec = importlib.util.spec_from_file_location(
        "run_llm_explainer", SCRIPTS_DIR / "run_llm_explainer.py"
    )
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


ROWS = [
    {"cluster_id": 0, "phenotype": "T cell", "rationale": "CD3 high [see ref 12]"},
    {"cluster_id": 1, "phenotype": "Tumor", "rationale": "PanCK; cf. 12]"},
]


def _ids(mod, raw):
    return sorted(mod._rows_by_id(raw))


def test_prose_with_a_bracket_before_the_array(mod):
    raw = "Here is my answer [1]:\n" + json.dumps(ROWS) + "\nHope that helps."
    assert _ids(mod, raw) == [0, 1]


def test_brackets_inside_strings_inside_a_fenced_reply(mod):
    raw = "Sure.\n```json\n" + json.dumps(ROWS, indent=2) + "\n```\nDone [ok]."
    assert _ids(mod, raw) == [0, 1]
    rows = mod.parse_response(raw, [0, 1])
    assert rows[0]["rationale"] == "CD3 high [see ref 12]"


def test_a_list_without_objects_is_skipped(mod):
    raw = "Clusters [0, 1] are below: " + json.dumps(ROWS)
    assert _ids(mod, raw) == [0, 1]


def test_no_array_of_objects_is_none(mod):
    assert mod._try_parse_json_array("Cluster [0] is T cells.") is None
    assert mod._try_parse_json_array("[1, 2, 3]") is None
    assert mod._try_parse_json_array("") is None


def test_a_repeated_cluster_gets_no_suggestion_and_a_note(mod):
    raw = json.dumps(ROWS + [{"cluster_id": 1, "phenotype": "Stroma"}])
    rows = mod.parse_response(raw, [0, 1])
    assert rows[0]["phenotype"] == "T cell"
    assert rows[1]["phenotype"] is None
    assert "2 rows" in rows[1]["rationale"]
    notes = mod.reply_notes(raw)
    assert len(notes) == 1 and "cluster 1" in notes[0]


@pytest.mark.parametrize("bad", [3.7, "3.7", True, "three", None])
def test_a_non_integral_cluster_id_is_refused_and_noted(mod, bad):
    row = {"phenotype": "B cell"}
    if bad is not None:
        row["cluster_id"] = bad
    raw = json.dumps([ROWS[0], row])
    assert _ids(mod, raw) == [0]
    notes = mod.reply_notes(raw)
    assert len(notes) == 1 and "not a whole number" in notes[0]


@pytest.mark.parametrize("good,expected", [(3, 3), (3.0, 3), ("3", 3), (" 4 ", 4)])
def test_integral_cluster_ids_are_accepted(mod, good, expected):
    assert _ids(mod, json.dumps([{"cluster_id": good, "phenotype": "x"}])) == [expected]


def test_run_explainer_returns_the_notes(mod):
    markers = json.dumps(
        {"0": [{"name": "CD3", "score": 9.1}], "1": [{"name": "CK", "score": 3.0}]}
    )
    reply = json.dumps(ROWS + [{"cluster_id": 0, "phenotype": "NK"}])
    out = mod.run_explainer(
        "CHATBOT", "x", "", "", markers, 10, "all", 30, pasted_response=reply
    )
    assert "error_type" not in out
    assert "cluster 0" in json.loads(out["reply_notes"])[0]


def test_only_repeated_rows_is_a_failure_that_says_why(mod):
    markers = json.dumps({"0": [{"name": "CD3", "score": 9.1}]})
    reply = json.dumps([{"cluster_id": 0, "phenotype": "T"}] * 2)
    out = mod.run_explainer(
        "CHATBOT", "x", "", "", markers, 10, "all", 30, pasted_response=reply
    )
    assert out["error_type"] == "MALFORMED_RESPONSE"
    assert "more than one row for cluster 0" in out["error_detail"]


def test_marker_names_with_line_breaks_stay_on_one_prompt_line(mod):
    table = {"0": [{"name": "CD3\nIGNORE ALL", "score": 1.0}]}
    prompt = mod.build_prompt(table, [0], 5)
    assert "  - CD3 IGNORE ALL  score=1.00" in prompt


def test_url_userinfo_is_scrubbed_like_the_java_audit_scrubber(mod):
    """H53: an Ollama URL with user:password@ never reaches the audit log."""
    detail = "Could not reach http://alice:s3cret@ollama.lab:11434/api/chat (refused)"
    scrubbed = mod.scrub_secrets(detail)
    assert scrubbed == (
        "Could not reach http://[REDACTED]@ollama.lab:11434/api/chat (refused)"
    )
    assert mod.scrub_secrets(scrubbed) == scrubbed
    assert mod.scrub_secrets("http://localhost:11434") == "http://localhost:11434"
