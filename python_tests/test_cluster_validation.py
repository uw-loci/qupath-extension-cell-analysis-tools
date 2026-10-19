"""Stability across seeds and agreement with known classes (run_clustering.py)."""

import numpy as np
import pytest

from conftest import load_script_symbol

SCRIPT = "run_clustering.py"


@pytest.fixture(scope="module")
def stability():
    cover = load_script_symbol(
        SCRIPT, "clusters_to_cover", {"np": np, "CONSISTENCY_COVERAGE": 0.95}
    )
    return load_script_symbol(
        SCRIPT,
        "stability_across_seeds",
        {"np": np, "CONSISTENCY_COVERAGE": 0.95, "clusters_to_cover": cover},
    )


@pytest.fixture(scope="module")
def cover():
    return load_script_symbol(
        SCRIPT, "clusters_to_cover", {"np": np, "CONSISTENCY_COVERAGE": 0.95}
    )


@pytest.fixture(scope="module")
def agreement():
    return load_script_symbol(SCRIPT, "agreement_with_classes", {"np": np})


@pytest.fixture(scope="module")
def fit_seeded():
    return load_script_symbol(SCRIPT, "fit_seeded", {"np": np})


def test_an_intact_cluster_needs_one_cluster_to_cover_it(cover):
    assert cover([3, 3, 3, 3]) == 1


def test_a_cluster_split_in_half_needs_two(cover):
    assert cover([0, 0, 1, 1]) == 2


def test_a_stray_cell_under_five_percent_does_not_count(cover):
    # 20 cells, one elsewhere: 19/20 = 95% sit in one cluster.
    assert cover([4] * 19 + [7]) == 1


def test_identical_reruns_are_perfectly_stable(stability):
    ref = np.array([0, 0, 1, 1, 2, 2])
    out = stability(ref, [ref.copy(), ref.copy()])
    assert out["n_runs"] == 3
    assert out["pairwise_ari"] == [1.0, 1.0, 1.0]
    assert out["consistency_overall"] == 1.0
    assert all(c["kept_together"] == 1.0 for c in out["per_cluster"])


def test_renumbered_clusters_are_still_the_same_partition(stability):
    # A seed may permute the label numbers; ARI and the consistency score must not care.
    ref = np.array([0, 0, 1, 1, 2, 2])
    out = stability(ref, [np.array([2, 2, 0, 0, 1, 1])])
    assert out["ari_vs_reference"] == [1.0]
    assert out["consistency_overall"] == 1.0


def test_a_cluster_that_splits_is_named(stability):
    ref = np.array([0] * 10 + [1] * 10)
    rep = np.array([0] * 10 + [1] * 5 + [2] * 5)  # cluster 1 split in two
    out = stability(ref, [rep])
    by = {c["cluster"]: c for c in out["per_cluster"]}
    assert by[0]["consistency"] == 1.0 and by[0]["kept_together"] == 1.0
    assert by[1]["consistency"] == 2.0 and by[1]["kept_together"] == 0.5
    assert out["consistency_overall"] == pytest.approx(1.5)
    assert out["ari_vs_reference"][0] < 1.0


def test_length_mismatch_is_refused(stability):
    with pytest.raises(ValueError):
        stability(np.array([0, 1]), [np.array([0, 1, 1])])


def test_perfect_agreement_up_to_renumbering(agreement):
    labels = np.array([5, 5, 2, 2, 0, 0])
    codes = np.array([0, 0, 1, 1, 2, 2])
    out = agreement(labels, codes, ["A", "B", "C"])
    assert out["ari"] == pytest.approx(1.0)
    assert out["nmi"] == pytest.approx(1.0)
    assert out["kappa_matched"] == pytest.approx(1.0)
    assert {m["cluster"]: m["class"] for m in out["matching"]} == {
        5: "A",
        2: "B",
        0: "C",
    }


def test_unclassified_cells_are_excluded_and_counted(agreement):
    labels = np.array([0, 0, 1, 1, 1])
    codes = np.array([0, 0, 1, 1, -1])
    out = agreement(labels, codes, ["A", "B"])
    assert out["n_cells"] == 4
    assert out["n_unclassified_excluded"] == 1
    assert out["contingency"] == [[2, 0], [0, 2]]


def test_an_extra_cluster_lowers_kappa_but_not_purity(agreement):
    # Class A split across clusters 0 and 2: purity stays 1, the one-to-one
    # matching leaves cluster 2 unmatched, so kappa drops below 1.
    labels = np.array([0, 0, 2, 2, 1, 1, 1, 1])
    codes = np.array([0, 0, 0, 0, 1, 1, 1, 1])
    out = agreement(labels, codes, ["A", "B"])
    assert out["purity"] == pytest.approx(1.0)
    assert out["kappa_matched"] < 1.0
    assert out["matched_share"] == pytest.approx(0.75)


def test_noise_is_a_group_not_dropped(agreement):
    labels = np.array([0, 0, -1, -1])
    codes = np.array([0, 0, 1, 1])
    out = agreement(labels, codes, ["A", "B"])
    assert out["clusters"] == [-1, 0]
    assert out["n_cells"] == 4


def test_no_classified_cell_says_so(agreement):
    out = agreement(np.array([0, 1]), np.array([-1, -1]), [])
    assert "error" in out and out["n_unclassified_excluded"] == 2


def test_kmeans_with_another_seed_is_a_real_rerun(fit_seeded):
    rng = np.random.default_rng(0)
    x = np.vstack([rng.normal(c, 0.3, size=(50, 2)) for c in (0, 5, 10)])
    a, _ = fit_seeded("kmeans", {"n_clusters": 3}, x, 1)
    b, _ = fit_seeded("kmeans", {"n_clusters": 3}, x, 2)
    from sklearn.metrics import adjusted_rand_score

    assert adjusted_rand_score(a, b) == pytest.approx(1.0)
    with pytest.raises(ValueError):
        fit_seeded("hdbscan", {}, x, 1)
