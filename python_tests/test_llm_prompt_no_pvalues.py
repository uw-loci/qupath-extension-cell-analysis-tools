"""The LLM explainer prompt must not hand the model an invalid significance claim.

The clusters are defined from the same marker measurements the Wilcoxon test
compares, so ``pval_adj`` is the p-value of a test whose null hypothesis the
clustering already falsified. Measured on scanpy 1.11.5 in the shipped
environment: on 600 cells of pure noise containing no clusters at all, Leiden
still found 11-13 groups and 47% of the marker rows a results table would show
came back at adjusted p < 0.05.

Sending that number to a language model invites it to write "significantly
enriched", which is how an invalid number becomes a sentence in a paper. These
tests pin the contract: no p-value reaches the prompt, the prompt tells the model
not to claim significance, and a future edit to the prompt text cannot ship
without bumping the template id that the audit log records.
"""

import ast

import pytest

from conftest import SCRIPTS_DIR, load_script_symbol

SCRIPT = "run_llm_explainer.py"


def _module_constant(name):
    """Read a top-level string assignment out of the shipped script."""
    tree = ast.parse((SCRIPTS_DIR / SCRIPT).read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id == name:
                    return ast.literal_eval(node.value)
    raise LookupError("No top-level %r in %s" % (name, SCRIPT))


@pytest.fixture
def build_prompt():
    return load_script_symbol(
        SCRIPT,
        "build_prompt",
        {"PROMPT_TEMPLATE": _module_constant("PROMPT_TEMPLATE")},
    )


MARKER_TABLE = {
    "0": [
        {"name": "CD3", "score": 9.9, "logfoldchange": 2.4, "pval_adj": 1.2345e-22},
        {"name": "CD8", "score": 7.1, "logfoldchange": 1.8, "pval_adj": 3.0e-11},
        {"name": "CD68", "score": -4.2, "logfoldchange": -1.5, "pval_adj": 5.5e-06},
    ],
    "1": [
        {"name": "PanCK", "score": 12.0, "logfoldchange": 3.1, "pval_adj": 0.0},
    ],
}


def _marker_lines(prompt):
    """Just the per-marker data rows, not the instructions around them.

    The instructions legitimately contain the words "p-value", because they say
    none are supplied and why. What must be clean is the data.
    """
    return [ln for ln in prompt.splitlines() if ln.startswith("  - ")]


def test_no_p_value_reaches_the_prompt(build_prompt):
    prompt = build_prompt(MARKER_TABLE, [0, 1], 10)
    rows = _marker_lines(prompt)
    assert len(rows) == 4
    for line in rows:
        assert "pval" not in line.lower()
        assert "p-value" not in line.lower()
    # The actual values, in every formatting the old code could have produced.
    data = "\n".join(rows)
    for forbidden in ("1.23e-22", "1.2345e-22", "3.00e-11", "5.50e-06", "0.00e+00"):
        assert forbidden not in data


def test_the_ranking_itself_still_reaches_the_prompt(build_prompt):
    # Removing the p-value must not remove what the model actually needs.
    prompt = build_prompt(MARKER_TABLE, [0, 1], 10)
    assert "CD3" in prompt and "CD8" in prompt and "PanCK" in prompt
    assert "score=9.90" in prompt
    assert "log2fc=2.40" in prompt


def test_a_negative_score_survives_with_its_sign(build_prompt):
    # A depleted marker is informative, and the sign is the whole of that
    # information. Printing 4.20 for CD68 would invert the finding.
    prompt = build_prompt(MARKER_TABLE, [0], 10)
    assert "score=-4.20" in prompt


def test_the_prompt_forbids_significance_language():
    template = _module_constant("PROMPT_TEMPLATE")
    lowered = template.lower()
    assert "defined from" in lowered
    assert "do not describe" in lowered
    assert "significant" in lowered  # named in order to be forbidden
    assert "p-value" in lowered  # ditto: says none are provided and why
    # The sign of the score has to be explained, since it is now the only
    # magnitude the model sees.
    assert "negative score" in lowered


def test_the_template_id_was_bumped_with_the_text():
    # The audit log records this id, and llm-explainer.md tells users to cite it
    # in a methods section. Two different prompts under one id would make every
    # archived run ambiguous.
    assert _module_constant("PROMPT_TEMPLATE_ID") == "cluster_phenotype_v2"


def test_top_n_still_truncates(build_prompt):
    prompt = build_prompt(MARKER_TABLE, [0], 2)
    assert "CD3" in prompt
    assert "CD8" in prompt
    assert "CD68" not in prompt
