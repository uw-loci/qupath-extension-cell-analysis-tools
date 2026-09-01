"""Feature selection and disclosure for the per-feature clustering plots.

The dotplot / matrixplot / stacked violin draw one column per feature, so a
compartment-heavy panel makes them unreadable and slow. Above a configurable
limit they show the most cluster-discriminative subset instead -- and the figure
has to SAY so, because a PNG showing 38 of 442 features otherwise reads as the
whole panel to anyone who drops it into a figure.
"""

from conftest import load_script_symbol

select = load_script_symbol("run_clustering.py", "select_plot_features")
note = load_script_symbol("run_clustering.py", "plot_feature_note")


def _ranked(n_clusters, n_features):
    feats = ["f%d" % i for i in range(n_features)]
    # Each cluster ranks the features in a different rotation, so the union is
    # genuinely per-cluster rather than the same head repeated.
    return {str(c): feats[c:] + feats[:c] for c in range(n_clusters)}


def test_small_panel_is_untouched():
    feats = ["a", "b", "c"]
    selected, subset = select(feats, _ranked(2, 3), 40)
    assert selected == feats
    assert subset is False


def test_at_the_limit_is_untouched():
    feats = ["f%d" % i for i in range(40)]
    selected, subset = select(feats, _ranked(4, 40), 40)
    assert selected == feats
    assert subset is False


def test_large_panel_is_subset_and_flagged():
    feats = ["f%d" % i for i in range(442)]
    selected, subset = select(feats, _ranked(5, 442), 40)
    assert subset is True
    assert len(selected) < len(feats)
    assert len(set(selected)) == len(selected), "no duplicates"
    assert set(selected).issubset(set(feats))


def test_every_cluster_is_represented():
    # An even share per cluster: no cluster may be squeezed out entirely, or the
    # plot would silently omit the markers defining one of the populations.
    feats = ["f%d" % i for i in range(200)]
    ranked = _ranked(8, 200)
    selected, _ = select(feats, ranked, 40)
    for cid, order in ranked.items():
        assert order[0] in selected, "cluster %s has no top feature shown" % cid


def test_no_ranking_falls_back_to_every_feature():
    # Single-cluster or embedding-only runs have no marker ranking; guessing an
    # ordering would be worse than plotting everything.
    feats = ["f%d" % i for i in range(100)]
    selected, subset = select(feats, {}, 40)
    assert selected == feats
    assert subset is False


def test_zero_or_negative_limit_disables_the_cap():
    feats = ["f%d" % i for i in range(100)]
    assert select(feats, _ranked(3, 100), 0) == (feats, False)
    assert select(feats, _ranked(3, 100), -1) == (feats, False)


def test_note_is_empty_when_nothing_was_dropped():
    assert note(40, 40, False) == ""


def test_note_states_both_counts():
    text = note(38, 442, True)
    assert "38" in text and "442" in text
