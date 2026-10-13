"""The MCP provider: an AI agent drives the same round trip a user does by hand.

``MCP`` must behave exactly like ``CHATBOT`` in ``run_explainer`` -- the empty
reply returns the prompt alone, a reply is parsed by the one parser, and no
network is touched -- so the explanations an agent stores say which prompt
produced them and are read back by the same code the clipboard path uses.
Both constants live in ``PASTE_PROVIDERS`` so one branch serves both.
"""

import importlib.util
import json

import pytest

from conftest import SCRIPTS_DIR


@pytest.fixture(scope="module")
def mod():
    spec = importlib.util.spec_from_file_location(
        "run_llm_explainer", SCRIPTS_DIR / "run_llm_explainer.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


MARKERS = json.dumps({
    "0": [{"name": "CD3", "score": 9.1, "logfoldchange": 2.0},
          {"name": "CD8", "score": 7.2, "logfoldchange": 1.5}],
    "1": [{"name": "PanCK", "score": 11.0, "logfoldchange": 3.1}],
})

REPLY = json.dumps([
    {"cluster_id": 0, "phenotype": "CD8 T cell", "confidence": "high",
     "rationale": "CD3 and CD8 lead.", "supporting_markers": ["CD3", "CD8"]},
    {"cluster_id": 1, "phenotype": "Tumor", "confidence": "medium",
     "rationale": "PanCK.", "supporting_markers": ["PanCK"]},
])


def test_mcp_and_chatbot_are_both_paste_providers(mod):
    assert set(mod.PASTE_PROVIDERS) == {"CHATBOT", "MCP"}


def test_an_empty_response_returns_the_prompt_alone_for_mcp(mod):
    out = mod.run_explainer("MCP", "Claude Code", "", "", MARKERS, 10, "all", 30,
                            pasted_response="")
    assert "error_type" not in out
    assert out["prompt"].startswith("You are a computational pathology assistant")
    assert out["explanations_json"] == ""
    assert len(out["prompt_hash"]) == 64


def test_mcp_prompt_only_output_is_identical_to_chatbot(mod):
    mcp = mod.run_explainer("MCP", "x", "", "", MARKERS, 10, "all", 30)
    chat = mod.run_explainer("CHATBOT", "x", "", "", MARKERS, 10, "all", 30)
    assert mcp == chat


def test_mcp_reply_is_parsed_identically_to_chatbot(mod):
    mcp = mod.run_explainer("MCP", "x", "", "", MARKERS, 10, "all", 30, pasted_response=REPLY)
    chat = mod.run_explainer("CHATBOT", "x", "", "", MARKERS, 10, "all", 30, pasted_response=REPLY)
    assert "error_type" not in mcp
    assert mcp == chat
    assert mcp["response_raw"] == REPLY
    assert mcp["input_tokens"] == -1 and mcp["output_tokens"] == -1
    rows = json.loads(mcp["explanations_json"])
    assert [r["phenotype"] for r in rows] == ["CD8 T cell", "Tumor"]


def test_provider_name_is_case_and_whitespace_insensitive(mod):
    out = mod.run_explainer(" mcp ", "x", "", "", MARKERS, 10, "all", 30, pasted_response=REPLY)
    assert "error_type" not in out
    assert len(json.loads(out["explanations_json"])) == 2


def test_mcp_never_touches_the_network_even_with_a_key_present(mod, monkeypatch):
    called = []
    monkeypatch.setattr(mod, "_call_anthropic", lambda *a, **k: called.append("anthropic"))
    monkeypatch.setattr(mod, "_call_ollama", lambda *a, **k: called.append("ollama"))
    mod.run_explainer("MCP", "x", "sk-ant-should-not-be-used", "http://x", MARKERS,
                      10, "all", 30, pasted_response="[]")
    assert called == []


def test_an_unsupported_provider_is_still_refused(mod):
    out = mod.run_explainer("CARRIER_PIGEON", "x", "", "", MARKERS, 10, "all", 30,
                            pasted_response=REPLY)
    assert out["error_type"] == "UNKNOWN"
    assert "Unsupported provider" in out["error_detail"]
