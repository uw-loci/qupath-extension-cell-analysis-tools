"""Soft membership: how marginal was each cell's hard label?

Every algorithm in `run_clustering.py` assigns each cell to exactly one cluster,
which hides gradients -- an EMT-like transition becomes an arbitrary line through
the middle of a continuum. Three algorithms already compute a per-cell quantity
that says how marginal the call was, and the script used to discard all three.

These are three DIFFERENT quantities and the tests hold them apart: a GMM
posterior is a probability, an HDBSCAN membership strength is a condensed-tree
persistence, and a KMeans margin is a distance ratio with no calibration at all.
Presenting the last as a probability would be inventing a number.
"""

import numpy as np
import pytest

from conftest import load_script_symbol

SCRIPT = "run_clustering.py"


@pytest.fixture
def posterior_confidence():
    return load_script_symbol(SCRIPT, "posterior_confidence", {"np": np})


@pytest.fixture
def distance_margin():
    return load_script_symbol(SCRIPT, "distance_margin", {"np": np})


# ---- posterior_confidence ----


def test_a_certain_assignment_scores_one_with_zero_entropy(posterior_confidence):
    out = posterior_confidence(np.array([[1.0, 0.0, 0.0], [0.0, 0.0, 1.0]]))
    assert out["best"] == pytest.approx([1.0, 1.0])
    assert out["entropy"] == pytest.approx([0.0, 0.0])


def test_a_coin_flip_scores_one_half(posterior_confidence):
    # The case the whole feature exists for: this cell and a 1.00/0.00 cell
    # carry the same hard label.
    out = posterior_confidence(np.array([[0.5, 0.5]]))
    assert out["best"] == pytest.approx([0.5])
    # Two equally likely components is maximal uncertainty for k=2.
    assert out["entropy"] == pytest.approx([1.0])
    assert out["runner_up_prob"] == pytest.approx([0.5])


def test_entropy_is_normalised_so_it_is_comparable_across_k(posterior_confidence):
    # A uniform posterior is entropy 1 whether there are 2 components or 10;
    # the raw Shannon entropy would be log(2) vs log(10) and not comparable.
    for k in (2, 3, 10):
        out = posterior_confidence(np.full((1, k), 1.0 / k))
        assert out["entropy"] == pytest.approx([1.0]), k
    assert np.all(posterior_confidence(np.eye(4))["entropy"] == pytest.approx(0.0))


def test_the_runner_up_is_the_second_most_likely_component(posterior_confidence):
    out = posterior_confidence(np.array([[0.1, 0.6, 0.3]]))
    assert out["best"] == pytest.approx([0.6])
    assert out["runner_up"].tolist() == [2]
    assert out["runner_up_prob"] == pytest.approx([0.3])


def test_rows_are_renormalised_rather_than_trusted(posterior_confidence):
    # sklearn's predict_proba rows sum to 1, but a row that does not must not
    # produce a "confidence" above 1.
    out = posterior_confidence(np.array([[2.0, 2.0], [8.0, 2.0]]))
    assert out["best"] == pytest.approx([0.5, 0.8])
    assert np.all(out["best"] <= 1.0)


def test_an_all_zero_row_reads_as_maximally_uncertain(posterior_confidence):
    # No information is not the same as a confident answer. Dividing by the row
    # sum here would give NaN, which downstream would render as a blank cell
    # rather than as the ambiguity it is.
    out = posterior_confidence(np.array([[0.0, 0.0, 0.0]]))
    assert np.isfinite(out["best"]).all()
    assert out["best"] == pytest.approx([1.0 / 3.0])
    assert out["entropy"] == pytest.approx([1.0])


def test_a_single_component_has_no_runner_up(posterior_confidence):
    out = posterior_confidence(np.array([[1.0], [1.0]]))
    assert out["runner_up"].tolist() == [-1, -1]
    assert out["entropy"] == pytest.approx([0.0, 0.0])


def test_posterior_confidence_rejects_malformed_input(posterior_confidence):
    with pytest.raises(ValueError):
        posterior_confidence(np.array([0.5, 0.5]))        # 1-D
    with pytest.raises(ValueError):
        posterior_confidence(np.zeros((0, 3)))            # no cells


# ---- distance_margin ----


def test_a_cell_on_a_centroid_has_full_margin(distance_margin):
    out = distance_margin(np.array([[0.0, 5.0], [7.0, 0.0]]))
    assert out["margin"] == pytest.approx([1.0, 1.0])
    assert out["runner_up"].tolist() == [1, 0]


def test_a_cell_exactly_between_two_centroids_has_zero_margin(distance_margin):
    out = distance_margin(np.array([[3.0, 3.0]]))
    assert out["margin"] == pytest.approx([0.0])


def test_the_margin_is_scale_free(distance_margin):
    # A distance ratio, so multiplying every distance by 1000 -- which is what
    # changing normalization does -- must not change the answer.
    d = np.array([[1.0, 4.0], [2.0, 2.5]])
    a = distance_margin(d)["margin"]
    b = distance_margin(d * 1000.0)["margin"]
    assert a == pytest.approx(b)
    assert a == pytest.approx([0.75, 0.2])


def test_the_margin_stays_in_range_and_never_divides_by_zero(distance_margin):
    # Two coincident centroids at distance 0 is degenerate but must not be NaN.
    out = distance_margin(np.array([[0.0, 0.0], [1.0, 2.0]]))
    assert np.isfinite(out["margin"]).all()
    assert np.all((out["margin"] >= 0.0) & (out["margin"] <= 1.0))


def test_one_cluster_is_full_confidence_not_zero(distance_margin):
    # With a single centroid there is nothing to be marginal between. Returning
    # 0 would read as "every cell is maximally ambiguous".
    out = distance_margin(np.array([[2.0], [9.0]]))
    assert out["margin"] == pytest.approx([1.0, 1.0])
    assert out["runner_up"].tolist() == [-1, -1]


def test_distance_margin_rejects_malformed_input(distance_margin):
    with pytest.raises(ValueError):
        distance_margin(np.array([1.0, 2.0]))             # 1-D
    with pytest.raises(ValueError):
        distance_margin(np.zeros((0, 2)))                 # no cells


# ---- The contract with the libraries that feed these ----


@pytest.mark.skipif(
    __import__("importlib").util.find_spec("sklearn") is None,
    reason="sklearn not installed",
)
def test_gmm_posterior_and_hard_labels_agree(posterior_confidence):
    # Pins the assumption the feature rests on: the hard label fit_predict gives
    # IS the argmax of predict_proba, so "confidence" describes the label that
    # was actually written to the cell. If sklearn ever decoupled them, the
    # column would silently describe a different cluster.
    from sklearn.mixture import GaussianMixture

    rng = np.random.default_rng(0)
    X = np.vstack([rng.normal(-3, 1, (120, 2)), rng.normal(3, 1, (120, 2))])
    gmm = GaussianMixture(n_components=2, random_state=0)
    labels = gmm.fit_predict(X)
    proba = gmm.predict_proba(X)
    assert np.array_equal(labels, proba.argmax(axis=1))

    out = posterior_confidence(proba)
    assert np.all(out["best"] >= 0.5)
    # Well-separated blobs: most cells should be near-certain.
    assert float(np.median(out["best"])) > 0.99


@pytest.mark.skipif(
    __import__("importlib").util.find_spec("sklearn") is None,
    reason="sklearn not installed",
)
def test_a_continuum_is_less_confident_than_separated_blobs(posterior_confidence):
    # The EMT case, measured rather than assumed: the output has to actually
    # distinguish a gradient from two populations, or it is decoration.
    #
    # Measured first, then asserted. The MEDIAN barely moves -- 1.0000 on
    # separated blobs against 0.9925 on a uniform continuum -- because most
    # points on a line are still nearer one end than the other. The share of
    # cells BELOW a threshold is what separates the two cases, and that is why
    # the results tab reports an ambiguous SHARE rather than an average:
    #
    #   statistic          blobs    continuum
    #   median            1.0000       0.9925
    #   mean              1.0000       0.9293
    #   10th percentile   1.0000       0.7329
    #   share below 0.9    0.000        0.225
    from sklearn.mixture import GaussianMixture

    rng = np.random.default_rng(1)
    blobs = np.vstack([rng.normal(-4, 0.5, (200, 2)), rng.normal(4, 0.5, (200, 2))])
    continuum = np.column_stack(
        [np.linspace(-4, 4, 400), rng.normal(0, 0.5, 400)]
    )

    def confidence(X):
        g = GaussianMixture(n_components=2, random_state=0)
        g.fit(X)
        return posterior_confidence(g.predict_proba(X))["best"]

    sharp = confidence(blobs)
    smooth = confidence(continuum)

    # The median is NOT the discriminating statistic -- this is the trap.
    assert float(np.median(sharp)) == pytest.approx(1.0, abs=1e-3)
    assert float(np.median(smooth)) > 0.98

    # The ambiguous share is. Separated populations leave nothing near a
    # boundary; a continuum leaves a fifth of its cells there.
    assert float(np.mean(sharp < 0.9)) == 0.0
    assert float(np.mean(smooth < 0.9)) > 0.15
    assert float(np.quantile(smooth, 0.10)) < 0.8


@pytest.mark.skipif(
    __import__("importlib").util.find_spec("sklearn") is None,
    reason="sklearn not installed",
)
def test_kmeans_transform_feeds_distance_margin(distance_margin):
    # Pins that KMeans.transform returns distances to centroids in cluster-index
    # order, which is what makes runner_up a usable cluster id.
    from sklearn.cluster import KMeans

    rng = np.random.default_rng(2)
    X = np.vstack([rng.normal(-5, 0.3, (100, 2)), rng.normal(5, 0.3, (100, 2))])
    km = KMeans(n_clusters=2, n_init=10, random_state=0)
    labels = km.fit_predict(X)
    d = km.transform(X)
    assert np.array_equal(labels, d.argmin(axis=1))

    out = distance_margin(d)
    assert np.all(out["runner_up"] != labels)
    assert float(np.median(out["margin"])) > 0.8


@pytest.mark.skipif(
    __import__("importlib").util.find_spec("sklearn") is None,
    reason="sklearn not installed",
)
def test_hdbscan_exposes_probabilities_and_noise_reads_as_zero():
    # The script reads hdb.probabilities_ directly, so the contract is sklearn's.
    from sklearn.cluster import HDBSCAN

    rng = np.random.default_rng(3)
    X = np.vstack([
        rng.normal(-6, 0.3, (80, 2)),
        rng.normal(6, 0.3, (80, 2)),
        rng.uniform(-10, 10, (20, 2)),      # scattered points, likely noise
    ])
    hdb = HDBSCAN(min_cluster_size=10)
    labels = hdb.fit_predict(X)
    assert hasattr(hdb, "probabilities_")
    p = np.asarray(hdb.probabilities_, dtype=float)
    assert p.shape == (X.shape[0],)
    assert np.all((p >= 0.0) & (p <= 1.0))
    if np.any(labels < 0):
        assert np.all(p[labels < 0] == 0.0)
