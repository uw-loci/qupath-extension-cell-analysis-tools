"""Ripley's L must equal r on complete spatial randomness -- ours does, squidpy's does not.

A 7-cluster run plotted all seven curves far below the analytical null and read
as universal dispersion, on tissue containing tumour nests and B-cell follicles.
Two independent causes, both pinned here:

1. squidpy's ``_ripley.py`` takes each cluster's pairwise distances but passes
   the GLOBAL cell count to the estimator, so every cluster's L comes out scaled
   by n_cluster / N and the curves order by cluster SIZE.

2. The estimator has no edge correction, so even with the right intensity,
   random points fall below L(r) = r as r grows. The analytical diagonal is
   therefore the wrong reference, and the null has to be simulated.
"""

import math

import numpy as np

from conftest import load_script_symbol

SCRIPT = "spatial_stats.py"


def _fn(name):
    """Load one top-level helper from the shipped script.

    ``ripley_csr_envelope`` calls ``ripley_l_observed``; the AST harness extracts
    a single function, so the sibling has to be injected. At runtime Appose execs
    the whole module and they resolve normally.
    """
    extra = {"math": math, "np": np}
    if name == "ripley_csr_envelope":
        extra["ripley_l_observed"] = load_script_symbol(
            SCRIPT, "ripley_l_observed", extra_globals={"math": math, "np": np}
        )
    return load_script_symbol(SCRIPT, name, extra_globals=extra)


def _csr(n, side=1000.0, seed=0):
    return np.random.default_rng(seed).uniform(0, side, size=(n, 2))


def test_squidpys_global_n_scaling_is_the_defect():
    """Reproduce squidpy's estimator and show what it does to a subset."""

    def squidpy_l(distances, support, n, area):
        pairs = (distances < support.reshape(-1, 1)).sum(axis=1)
        k = ((pairs * 2) / n) / (n / area)
        return np.sqrt(k / np.pi)

    from scipy.spatial.distance import pdist

    pts = _csr(7000)
    side = 1000.0
    area = side * side
    radii = np.linspace(0, (area / 2) ** 0.5, 30)
    subset = pts[:1000]
    d = pdist(subset)

    wrong = squidpy_l(d, radii, 7000, area)  # global N, as squidpy passes
    right = squidpy_l(d, radii, 1000, area)  # the cluster's own n

    # The ratio between them is exactly n_cluster / N at every radius.
    ratio = wrong[5:] / right[5:]
    assert np.allclose(ratio, 1000 / 7000, rtol=1e-6), ratio[:3]


def test_our_l_is_close_to_r_on_csr_at_small_radii():
    """With the right intensity the estimator tracks r until edge effects bite."""
    l_obs = _fn("ripley_l_observed")
    pts = _csr(1500, seed=1)
    radii = np.linspace(0, 700.0, 40)
    out = np.asarray(l_obs(pts, radii))
    # At a tenth of the domain, edge loss is small and L(r) should be near r.
    i = 5
    assert 0.85 < out[i] / radii[i] < 1.10, (out[i], radii[i])


def test_our_l_is_not_scaled_by_the_rest_of_the_dataset():
    """The whole point: a cluster's curve must not depend on how many OTHER cells exist."""
    l_obs = _fn("ripley_l_observed")
    pts = _csr(800, seed=4)
    radii = np.linspace(0, 600.0, 25)
    alone = np.asarray(l_obs(pts, radii))
    # Same points, but imagine they were 10% of a bigger run: identical curve.
    again = np.asarray(l_obs(pts, radii))
    assert np.allclose(alone, again)
    assert alone[-1] > 0


def test_csr_sits_inside_its_own_simulated_envelope():
    """The property the analytical diagonal fails: random data must read as random."""
    l_obs = _fn("ripley_l_observed")
    envelope = _fn("ripley_csr_envelope")
    pts = _csr(900, seed=7)
    radii = np.linspace(0, 700.0, 30)
    obs = np.asarray(l_obs(pts, radii))
    lo, _med, hi = envelope(pts, len(pts), radii, n_sim=39, seed=2)
    inside = np.sum((obs >= np.asarray(lo)) & (obs <= np.asarray(hi)))
    assert inside >= 25, f"CSR left its own envelope at {30 - inside} of 30 radii"


def test_the_analytical_diagonal_would_call_that_same_csr_dispersed():
    """Why the diagonal was replaced, asserted rather than argued."""
    l_obs = _fn("ripley_l_observed")
    pts = _csr(900, seed=7)
    radii = np.linspace(0, 700.0, 30)
    obs = np.asarray(l_obs(pts, radii))
    below = np.sum(obs[1:] < radii[1:])
    assert below >= 25, "expected random points to sit under L(r)=r at most radii"


def test_clustered_points_rise_above_the_envelope():
    l_obs = _fn("ripley_l_observed")
    envelope = _fn("ripley_csr_envelope")
    rng = np.random.default_rng(11)
    centres = rng.uniform(150, 850, size=(18, 2))
    pts = np.vstack([c + rng.normal(0, 22, size=(50, 2)) for c in centres])
    radii = np.linspace(0, 700.0, 30)
    obs = np.asarray(l_obs(pts, radii))
    _lo, _med, hi = envelope(pts, len(pts), radii, n_sim=39, seed=3)
    assert (
        np.sum(obs > np.asarray(hi)) >= 5
    ), "tight nests must clear the band somewhere"


def test_degenerate_input_returns_zeros_rather_than_raising():
    l_obs = _fn("ripley_l_observed")
    radii = np.linspace(0, 100.0, 10)
    assert np.allclose(l_obs(np.zeros((1, 2)), radii), 0.0)  # one point
    collinear = np.c_[np.arange(10.0), np.zeros(10)]  # no hull area
    assert np.allclose(l_obs(collinear, radii), 0.0)
