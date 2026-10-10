"""Under z-score a dotplot's dot size is not the fraction expressing.

``sc.pl.dotplot`` sizes each dot by ``obs_tidy > expression_cutoff`` with the
cutoff at 0.0, reading ``adata.X``. QP-CAT puts the NORMALISED matrix there, so
the quantity moves with the Normalization setting -- while the tab guide, the
docs and the figure legend all called it "fraction of cells expressing".

Two regimes, both measured, and the second one corrects the first:

1. On continuous measurements with no zero floor (lognormal intensities, 3
   clusters x 6 markers) the spread of the fraction was zscore 0.837, percentile
   0.021, minmax 0.001, none 0.000 -- so in three modes every dot is drawn at
   maximum size and the encoding says nothing.

2. On 3,098 REAL cells from the synthetic TME project, every mode was
   informative (spread 0.458 to 0.938), because multiplex intensities have a
   hard zero for cells that do not carry the marker -- 1,893 to 2,861 of those
   cells sit at exactly 0 for each lineage marker. There, above-the-cutoff
   really is "has any signal".

So the defect is narrower than it first looked, and the fix is measurement
rather than a rule about modes: the figure is skipped only when the spread is
actually nil, and the caption names what the size means. Under z-score -- the
default -- it says plainly that this is the fraction above the marker's cohort
mean, which on that real data is 0.495 for DAPI against 1.000 expressing.
"""

import math

import numpy as np
import pytest

from conftest import load_script_symbol

SCRIPT = "run_clustering.py"


def _fn(name):
    return load_script_symbol(
        SCRIPT, name, extra_globals={"np": np, "math": math}
    )


def _normalise(df, mode):
    """The four branches of run_clustering.py's normalization step."""
    if mode == "zscore":
        std = df.std().replace(0, 1).fillna(1)
        return (df - df.mean().fillna(0.0)) / std
    if mode == "minmax":
        dmin, dmax = df.min(), df.max()
        rng = dmax - dmin
        rng[rng == 0] = 1
        return (df - dmin) / rng
    if mode == "percentile":
        p1, p99 = df.quantile(0.01), df.quantile(0.99)
        rng = p99 - p1
        rng[rng == 0] = 1
        clipped = df.clip(lower=p1, upper=p99, axis=1)
        return (clipped - p1) / rng
    return df.copy()


def _intensities(n=3000, m=6, seed=3):
    """Lognormal intensities with three cell types, two markers raised each."""
    pd = pytest.importorskip("pandas")
    rng = np.random.default_rng(seed)
    base = rng.lognormal(mean=3.0, sigma=0.6, size=(n, m))
    ctype = rng.integers(0, 3, n)
    for t in range(3):
        base[ctype == t, 2 * t:2 * t + 2] *= 6.0
    return pd.DataFrame(base, columns=["M%d" % j for j in range(m)]), ctype


def test_without_a_zero_floor_three_of_four_normalisations_are_degenerate():
    fractions_fn = _fn("dot_size_fractions")
    readable_fn = _fn("dot_size_is_readable")
    df, ctype = _intensities()

    measured = {}
    for mode in ("zscore", "minmax", "percentile", "none"):
        fr = fractions_fn(_normalise(df, mode).to_numpy(), ctype, cutoff=0.0)
        measured[mode] = (min(fr), float(np.median(fr)), max(fr))

    # The one mode where the encoding varies.
    assert readable_fn(
        fractions_fn(_normalise(df, "zscore").to_numpy(), ctype)
    ), measured

    # The three where every dot is at maximum size.
    for mode in ("minmax", "percentile", "none"):
        lo, _, hi = measured[mode]
        assert lo > 0.95, (mode, measured[mode])
        assert not readable_fn(
            fractions_fn(_normalise(df, mode).to_numpy(), ctype)
        ), (mode, measured[mode])


def test_with_a_zero_floor_every_normalisation_is_informative():
    """The real-data regime: a hard zero for cells that lack the marker.

    This is why the guard measures instead of switching on the normalization --
    the same mode is degenerate on one matrix and informative on another.
    """
    pd = pytest.importorskip("pandas")
    fractions_fn = _fn("dot_size_fractions")
    readable_fn = _fn("dot_size_is_readable")
    rng = np.random.default_rng(5)
    n, m = 3000, 6
    ctype = rng.integers(0, 3, n)
    base = np.zeros((n, m))
    for t in range(3):
        # each type carries two markers; the rest stay at exactly zero
        rows = ctype == t
        base[np.ix_(rows, [2 * t, 2 * t + 1])] = rng.lognormal(
            3.0, 0.6, (rows.sum(), 2)
        )
    df = pd.DataFrame(base, columns=["M%d" % j for j in range(m)])

    for mode in ("zscore", "minmax", "percentile", "none"):
        fr = fractions_fn(_normalise(df, mode).to_numpy(), ctype, cutoff=0.0)
        assert readable_fn(fr), (mode, min(fr), max(fr))


def test_under_zscore_the_fraction_is_not_the_fraction_expressing():
    """The residual defect, on data where "expressing" is well defined."""
    pd = pytest.importorskip("pandas")
    fractions_fn = _fn("dot_size_fractions")
    rng = np.random.default_rng(6)
    n = 4000
    # One marker every cell carries, like DAPI.
    values = rng.lognormal(3.0, 0.6, (n, 1))
    df = pd.DataFrame(values, columns=["DAPI"])
    labels = np.zeros(n, dtype=int)

    expressing = float((df["DAPI"] > 0).mean())
    assert expressing == 1.0

    zscored = fractions_fn(_normalise(df, "zscore").to_numpy(), labels)[0]
    raw = fractions_fn(df.to_numpy(), labels)[0]

    assert raw == pytest.approx(1.0)
    # Centring moves the cutoff onto the mean; a right-skewed marker puts
    # fewer than half the cells above it.
    assert zscored < 0.55
    assert abs(zscored - expressing) > 0.4


def test_dot_size_fractions_matches_scanpys_own_computation():
    """Pins the thing being reimplemented: obs_tidy > cutoff, per group."""
    fractions_fn = _fn("dot_size_fractions")
    matrix = np.array(
        [[-1.0, 2.0], [1.0, 2.0], [3.0, -2.0], [-3.0, -2.0]], dtype=float
    )
    labels = np.array(["a", "a", "b", "b"])
    # group a: M0 -> 1 of 2 above 0, M1 -> 2 of 2
    # group b: M0 -> 1 of 2, M1 -> 0 of 2
    assert fractions_fn(matrix, labels, cutoff=0.0) == pytest.approx(
        [0.5, 1.0, 0.5, 0.0]
    )


def test_a_constant_encoding_is_not_readable():
    readable_fn = _fn("dot_size_is_readable")
    assert not readable_fn([1.0] * 20)
    assert not readable_fn([0.99, 1.0, 0.995])
    assert not readable_fn([])
    assert not readable_fn([float("nan"), float("nan")])
    assert readable_fn([0.02, 0.9])


def test_the_threshold_sits_in_an_empty_gap():
    """The default must separate the measured cases with room on both sides.

    Degenerate spreads top out near 0.021 and the informative one is near 0.84,
    so the exact value does not matter -- but a future edit that moved it into
    either cluster would silently change which figures are drawn.
    """
    readable_fn = _fn("dot_size_is_readable")
    assert not readable_fn([0.0, 0.025])
    assert readable_fn([0.0, 0.80])


def test_the_caption_names_the_cutoff_and_the_units():
    caption_fn = _fn("dot_size_caption")
    for mode, expect in (
        ("minmax", "[0, 1]"),
        ("percentile", "percentile"),
        ("none", "raw measurement units"),
    ):
        text = caption_fn(mode, 0.0)
        assert "dot size = fraction" in text
        assert mode in text
        assert expect in text
        # These modes keep the measurement's zero, so the usual reading holds
        # and the caption must not deny it.
        assert "not the fraction expressing" not in text


def test_the_zscore_caption_says_it_is_not_the_fraction_expressing():
    """The default mode, and the one place the old label was simply wrong."""
    text = _fn("dot_size_caption")("zscore", 0.0)
    assert "COHORT MEAN" in text
    assert "not the fraction expressing" in text
