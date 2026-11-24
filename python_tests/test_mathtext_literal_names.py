"""Names holding "$" are drawn as written, not parsed as math (H52).

A class, cluster, marker or image name with two "$" and a "^" or "{" between them
made matplotlib parse it as mathtext and raise at savefig; every figure site caught
that as "plot failed" and the PNG went missing. The worker turns mathtext off once
(init_services.py) and the bold marker labels opt back in per label, escaping "$".
"""

import re

import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires

NASTY = "slide $a^{b$ (CD8)"


def test_the_worker_turns_mathtext_off_at_init():
    src = (SCRIPTS_DIR / "init_services.py").read_text(encoding="utf-8")
    use = src.index('matplotlib.use("Agg")')
    off = re.search(r'matplotlib\.rcParams\["text\.parse_math"\]\s*=\s*False', src)
    assert off is not None and off.start() > use


@requires("matplotlib")
def test_a_dollar_name_breaks_savefig_with_mathtext_and_not_without(tmp_path):
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    with matplotlib.rc_context({"text.parse_math": True}):
        fig, ax = plt.subplots()
        ax.set_title(NASTY)
        with pytest.raises(ValueError):
            fig.savefig(tmp_path / "math.png")
        plt.close(fig)
    with matplotlib.rc_context({"text.parse_math": False}):
        fig, ax = plt.subplots()
        ax.set_title(NASTY)
        ax.set_xticks([0])
        ax.set_xticklabels([NASTY])
        fig.savefig(tmp_path / "literal.png")
        assert ax.title.get_text() == NASTY
        plt.close(fig)


@requires("matplotlib")
def test_bold_marker_ticks_still_bold_with_mathtext_off(tmp_path):
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    split = load_script_symbol("model_utils.py", "split_measurement_name")
    bold = load_script_symbol(
        "model_utils.py", "bold_marker_label", {"split_measurement_name": split}
    )
    ticks = load_script_symbol(
        "model_utils.py", "bold_marker_ticks", {"bold_marker_label": bold}
    )
    with matplotlib.rc_context({"text.parse_math": False}):
        fig, ax = plt.subplots()
        ax.bar(range(2), [1, 2])
        ax.set_xticks(range(2))
        ax.set_xticklabels(["Nucleus: CD3 mean", "Cell: $CD8^{x$ mean"])
        ticks(ax)
        fig.savefig(tmp_path / "ticks.png")
        labels = ax.get_xticklabels()
        assert all(t.get_parse_math() for t in labels)
        assert labels[0].get_text() == r"Nucleus: $\mathbf{CD3}$ mean"
        assert labels[1].get_text() == r"Cell: \$CD8^{x\$ mean"
        plt.close(fig)
