"""The CHATBOT provider: the prompt leaves by clipboard, the reply comes back by paste.

No key, no network. The point of the test is the ONE-CONTRACT rule: the prompt
a user carries to claude.ai or ChatGPT is the same ``build_prompt`` output the
live providers get, and the pasted reply goes through the same
``parse_response``. Two code paths here would drift, and the explanations saved
with a result would no longer say which prompt produced them.

Runs the whole module (not an AST-extracted helper) because ``run_explainer``
is the entry point under test and it reaches most of the file. The module's
Appose tail is guarded by ``except NameError`` so importing it is safe.
"""

import importlib.util
import json

import pytest

from conftest import SCRIPTS_DIR


@pytest.fixture(scope="module")
def mod():
    spec = importlib.util.spec_from_file_location(
        "run_llm_explainer", SCRIPTS_DIR / "run_llm_explainer.py"
    )
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


MARKERS = json.dumps(
    {
        "0": [
            {"name": "CD3", "score": 9.1, "logfoldchange": 2.0},
            {"name": "CD8", "score": 7.2, "logfoldchange": 1.5},
        ],
        "1": [{"name": "PanCK", "score": 11.0, "logfoldchange": 3.1}],
    }
)


def test_an_empty_paste_returns_the_prompt_and_nothing_else(mod):
    out = mod.run_explainer(
        "CHATBOT", "claude.ai", "", "", MARKERS, 10, "all", 30, pasted_response=""
    )
    assert "error_type" not in out
    assert out["prompt"].startswith("You are a computational pathology assistant")
    assert "CD3" in out["prompt"] and "PanCK" in out["prompt"]
    assert out["explanations_json"] == ""
    assert out["response_raw"] == ""
    assert len(out["prompt_hash"]) == 64


def test_the_prompt_handed_to_a_chatbot_is_the_one_the_api_gets(mod):
    by_hand = mod.run_explainer("CHATBOT", "x", "", "", MARKERS, 10, "all", 30)[
        "prompt"
    ]
    direct = mod.build_prompt(json.loads(MARKERS), [0, 1], 10)
    assert by_hand == direct


def test_a_pasted_reply_is_parsed_by_the_same_parser(mod):
    reply = json.dumps(
        [
            {
                "cluster_id": 0,
                "phenotype": "CD8 T cell",
                "confidence": "high",
                "rationale": "CD3 and CD8 lead.",
                "supporting_markers": ["CD3", "CD8"],
            },
            {
                "cluster_id": 1,
                "phenotype": "Tumor",
                "confidence": "medium",
                "rationale": "PanCK.",
                "supporting_markers": ["PanCK"],
            },
        ]
    )
    out = mod.run_explainer(
        "CHATBOT", "ChatGPT", "", "", MARKERS, 10, "all", 30, pasted_response=reply
    )
    assert "error_type" not in out
    assert out["response_raw"] == reply
    assert out["input_tokens"] == -1 and out["output_tokens"] == -1
    rows = json.loads(out["explanations_json"])
    assert [r["phenotype"] for r in rows] == ["CD8 T cell", "Tumor"]
    assert rows == mod.parse_response(reply, [0, 1])


def test_a_reply_wrapped_in_markdown_fences_still_parses(mod):
    # Chatbots fence JSON by default; a user should not have to strip it.
    reply = (
        'Here you go:\n```json\n[{"cluster_id": 0, "phenotype": "T cell", '
        '"confidence": "low", "rationale": "r", "supporting_markers": []}]\n```'
    )
    out = mod.run_explainer(
        "CHATBOT", "x", "", "", MARKERS, 10, "[0]", 30, pasted_response=reply
    )
    rows = json.loads(out["explanations_json"])
    assert rows[0]["phenotype"] == "T cell"


def test_pasting_prose_instead_of_json_gives_a_row_per_cluster_not_a_crash(mod):
    out = mod.run_explainer(
        "CHATBOT",
        "x",
        "",
        "",
        MARKERS,
        10,
        "all",
        30,
        pasted_response="Sorry, I cannot help with that.",
    )
    rows = json.loads(out["explanations_json"])
    assert len(rows) == 2
    assert all(r["phenotype"] is None for r in rows)
    assert all("did not return a row" in r["rationale"] for r in rows)
    # ...and the caller is told it failed, so the panel cannot report "Done".
    assert out["error_type"] == "MALFORMED_RESPONSE"


def test_chatbot_never_touches_the_network_even_with_a_key_present(mod, monkeypatch):
    called = []
    monkeypatch.setattr(
        mod, "_call_anthropic", lambda *a, **k: called.append("anthropic")
    )
    monkeypatch.setattr(mod, "_call_ollama", lambda *a, **k: called.append("ollama"))
    mod.run_explainer(
        "CHATBOT",
        "x",
        "sk-ant-should-not-be-used",
        "http://x",
        MARKERS,
        10,
        "all",
        30,
        pasted_response="[]",
    )
    assert called == []


def test_a_null_rationale_is_stored_as_empty_not_the_word_none(mod):
    reply = json.dumps(
        [
            {
                "cluster_id": 0,
                "phenotype": "T cell",
                "confidence": "high",
                "rationale": None,
                "supporting_markers": ["CD3"],
            },
            {
                "cluster_id": 1,
                "phenotype": "Tumor",
                "confidence": "low",
                "rationale": "PanCK high",
                "supporting_markers": [],
            },
        ]
    )
    out = mod.run_explainer(
        "MCP", "agent", "", "", MARKERS, 10, "all", 30, pasted_response=reply
    )
    rows = json.loads(out["explanations_json"])
    assert rows[0]["rationale"] == ""
    assert rows[1]["rationale"] == "PanCK high"
