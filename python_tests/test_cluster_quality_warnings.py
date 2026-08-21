"""A clustering run can finish cleanly and still be useless.

HDBSCAN on 304,083 cells of TMA morphometry returned one real cluster (77.9%
of cells), a 15-cell cluster, and 22.1% noise -- and reported it as "3 clusters
over 304083 cells", because the noise group was counted as a cluster. Every
plot rendered. Nothing in the pipeline noticed. The user found out by looking
at the viewer and seeing one colour.

``cluster_quality_warnings`` is the check that was missing: it reads the label
array the algorithm actually produced and says so in words.
"""

import numpy as np

from conftest import load_script_symbol

SCRIPT = "run_clustering.py"


def _warn(labels, algorithm="hdbscan"):
    fn = load_script_symbol(
        SCRIPT, "cluster_quality_warnings", extra_globals={"np": np}
    )
    return fn(np.asarray(labels), algorithm)


def test_balanced_partition_is_silent():
    labels = np.repeat(np.arange(8), 500)
    assert _warn(labels, "leiden") == []


def test_single_cluster_is_flagged():
    out = _warn(np.zeros(1000, dtype=int), "kmeans")
    assert out, "a one-cluster result must not pass silently"
    assert "ONE cluster" in out[0]


def test_all_noise_is_flagged():
    out = _warn(-np.ones(1000, dtype=int))
    assert "No clusters were found" in out[0]


def test_dominant_cluster_is_flagged():
    # 90% in cluster 0, 10% split over two others: technically three clusters,
    # practically one.
    labels = np.concatenate(
        [np.zeros(900, dtype=int), np.ones(50, dtype=int), np.full(50, 2)]
    )
    out = _warn(labels, "kmeans")
    assert any("holds 90.0% of the clustered cells" in w for w in out)


def test_noise_fraction_is_reported_with_the_count():
    labels = np.concatenate([np.zeros(700, dtype=int), -np.ones(300, dtype=int)])
    out = _warn(labels)
    assert any("300 cells (30.0%)" in w for w in out)


def test_the_real_run_is_flagged_on_every_count():
    """The shipped 2026-08-21 result: 236,840 / 15 / 67,228 noise."""
    labels = np.concatenate(
        [
            np.zeros(236840, dtype=int),
            np.ones(15, dtype=int),
            -np.ones(67228, dtype=int),
        ]
    )
    out = _warn(labels, "hdbscan")
    joined = " ".join(out)
    assert "100.0% of the clustered cells (236840 of 236855)" in joined
    assert "67228 cells (22.1%)" in joined  # noise
    assert "fewer than" in joined  # the 15-cell cluster
    assert "GAP IN DENSITY" in joined  # the actionable explanation


def test_advice_is_algorithm_specific():
    labels = np.zeros(1000, dtype=int)
    assert "HDBSCAN" in " ".join(_warn(labels, "hdbscan"))
    assert "HDBSCAN" not in " ".join(_warn(labels, "kmeans"))


def test_empty_input_does_not_raise():
    assert _warn(np.zeros(0, dtype=int), "leiden") == []
