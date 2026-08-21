"""Per-area output: what belongs to an area, and what belongs to a cluster.

The split follows what a measurement is a property OF. A cluster's marker
profile is a property of the cluster -- identical in every area -- so it stays
global and is reported once. Composition, Ripley's L and co-occurrence are
properties of an area, so they get one row per area.

Ripley and co-occurrence also have to be LOOPED per area rather than
inheriting the partition: they read obsm['spatial'] directly and never consult
the neighbour graph, so a block-diagonal graph does nothing for them. Pooling a
TMA's cores into one point pattern measures the layout of the array rather than
the biology of any core -- the convex hull spans the slide and every inter-core
gap reads as dispersion.
"""

import json

import numpy as np
import pytest

from conftest import load_script_symbol, requires

SCRIPT = "spatial_stats.py"


class _Logger:
    def __init__(self):
        self.warnings = []

    def warning(self, msg, *args):
        self.warnings.append(msg % args if args else msg)

    def info(self, msg, *args):
        pass


def _summary_builder():
    ns = {
        "np": np,
        "logger": _Logger(),
        "area_slices": load_script_symbol(SCRIPT, "area_slices", {"np": np}),
        "area_label": load_script_symbol(SCRIPT, "area_label", {"np": np}),
        "area_type": load_script_symbol(SCRIPT, "area_type", {"np": np}),
        "_csv_cell": load_script_symbol(SCRIPT, "_csv_cell", {"np": np}),
    }
    ns["_csv_row"] = load_script_symbol(SCRIPT, "_csv_row", ns)
    return load_script_symbol(SCRIPT, "build_area_summary_csv", ns)


def _statistics_builder(logger=None):
    ns = {"np": np, "logger": logger or _Logger()}
    ns["_csv_cell"] = load_script_symbol(SCRIPT, "_csv_cell", ns)
    ns["_csv_row"] = load_script_symbol(SCRIPT, "_csv_row", ns)
    return load_script_symbol(SCRIPT, "build_area_statistics_csv", ns)


def _rows(csv_text):
    return [line for line in csv_text.strip().split("\n")]


# --- Wide summary --------------------------------------------------------


def test_summary_has_one_row_per_area():
    csv = _summary_builder()(
        area_ids=[0, 0, 0, 1, 1],
        area_names=["slide | A-1", "slide | A-2"],
        cluster_labels=[0, 1, 0, 1, 1],
    )
    rows = _rows(csv)
    assert len(rows) == 3  # header + 2 areas
    # area, type, n_cells, n_clusters_present
    assert rows[1].startswith("slide | A-1,Image,3,2")
    assert rows[2].startswith("slide | A-2,Image,2,1")


def test_summary_fractions_are_within_the_area_not_the_cohort():
    # Area 0 is 2/3 cluster 0; area 1 is 0/2. A cohort-wide fraction would be
    # 2/5 for both, which is the number a pooled report would give.
    csv = _summary_builder()(
        area_ids=[0, 0, 0, 1, 1],
        area_names=None,
        cluster_labels=[0, 0, 1, 1, 1],
    )
    rows = _rows(csv)
    header = rows[0].split(",")
    frac0 = header.index("Cluster 0_frac")
    assert rows[1].split(",")[frac0] == "%.6f" % (2 / 3)
    assert rows[2].split(",")[frac0] == "%.6f" % 0.0


def test_summary_counts_sum_to_the_area_cell_count():
    csv = _summary_builder()(
        area_ids=[0, 0, 1, 1, 1, 2],
        area_names=None,
        cluster_labels=[0, 1, 1, 2, 2, 0],
    )
    rows = _rows(csv)
    header = rows[0].split(",")
    count_cols = [i for i, h in enumerate(header) if h.endswith("_count")]
    for row in rows[1:]:
        cells = row.split(",")
        n_cells_col = header.index("n_cells")
        assert sum(int(cells[i]) for i in count_cols) == int(cells[n_cells_col])


def test_summary_includes_clusters_absent_from_an_area_as_zero():
    # A missing column would read as "not measured"; an explicit 0 says the
    # cluster genuinely is not in that core.
    csv = _summary_builder()(area_ids=[0, 1], area_names=None, cluster_labels=[0, 1])
    header = _rows(csv)[0]
    assert "Cluster 0_frac" in header and "Cluster 1_frac" in header
    assert _rows(csv)[1].split(",")[header.split(",").index("Cluster 1_count")] == "0"


def test_summary_uses_supplied_cluster_names():
    csv = _summary_builder()(
        area_ids=[0, 1],
        area_names=None,
        cluster_labels=[0, 1],
        cluster_names=["Tumor", "Stroma"],
    )
    assert "Tumor_frac" in _rows(csv)[0]
    assert "Stroma_count" in _rows(csv)[0]


def test_area_labels_containing_commas_are_quoted():
    csv = _summary_builder()(
        area_ids=[0], area_names=["slide, block 2 | A-1"], cluster_labels=[0]
    )
    assert '"slide, block 2 | A-1"' in csv


def test_type_column_names_what_each_area_is():
    # A name alone is not self-describing once a project mixes cores and
    # annotations -- "A-1" tells a reader nothing on its own.
    csv = _summary_builder()(
        area_ids=[0, 1],
        area_names=["slide | A-1", "slide | Tumor"],
        cluster_labels=[0, 1],
        area_types=["TMA Core", "Annotation-Tumor"],
    )
    rows = _rows(csv)
    assert rows[0].split(",")[1] == "type"
    assert rows[1].split(",")[1] == "TMA Core"
    assert rows[2].split(",")[1] == "Annotation-Tumor"


def test_type_defaults_to_image_when_absent():
    # No types means the run was not partitioned below the image level, which
    # is exactly when every area IS an image.
    csv = _summary_builder()(area_ids=[0], area_names=None, cluster_labels=[0])
    assert _rows(csv)[1].split(",")[1] == "Image"


def test_summary_refuses_a_length_mismatch():
    with pytest.raises(ValueError, match="does not match"):
        _summary_builder()(area_ids=[0, 0, 1], area_names=None, cluster_labels=[0, 1])


def test_area_label_falls_back_to_a_stable_synthetic_name():
    label = load_script_symbol(SCRIPT, "area_label", {"np": np})
    assert label(["one", "two"], 1) == "two"
    assert label(None, 3) == "Area 3"
    assert label(["one"], 5) == "Area 5"  # short list, not an exception


# --- Long statistics -----------------------------------------------------


def test_statistics_csv_emits_a_row_per_area_and_key():
    csv = _statistics_builder()(
        {
            "ripley": {
                "slide | A-1": json.dumps({"p_values": {"0": 0.01, "1": 0.2}}),
                "slide | A-2": json.dumps({"p_values": {"0": 0.5}}),
            }
        }
    )
    rows = _rows(csv)
    assert rows[0] == "area,statistic,key,value,p_value"
    body = rows[1:]
    assert any(r.startswith("slide | A-1,ripley,0,,0.01") for r in body)
    assert any(r.startswith("slide | A-2,ripley,0,,0.5") for r in body)


def test_statistics_csv_accepts_already_decoded_payloads():
    csv = _statistics_builder()({"ripley": {"A": {"p_values": {"0": 0.03}}}})
    assert "A,ripley,0,,0.03" in csv


def test_an_area_with_no_p_values_still_gets_a_row():
    """Omitting it would read as 'not measured' when it means 'no p-values'."""
    csv = _statistics_builder()({"ripley": {"A": {"radii": [1, 2, 3]}}})
    assert "A,ripley,computed,1," in csv


def test_unparseable_payload_is_warned_not_silently_dropped():
    log = _Logger()
    csv = _statistics_builder(log)({"ripley": {"A": "{not json"}})
    assert len(_rows(csv)) == 1  # header only
    assert any("not valid JSON" in w for w in log.warnings)


def test_statistics_are_ordered_deterministically():
    payload = {
        "co_occurrence_pairwise": {"B": {"p_values": {"1": 0.1}}},
        "ripley": {"A": {"p_values": {"0": 0.2}}},
    }
    first = _statistics_builder()(payload)
    second = _statistics_builder()(payload)
    assert first == second
    # Sorted by statistic name, so co_occurrence precedes ripley.
    assert first.index("co_occurrence_pairwise") < first.index("ripley")


# --- Ripley actually returns curves --------------------------------------
#
# On squidpy 1.6.6 the L result lives under uns["<key>_ripley_L"]["L_stat"],
# not ["stats"]. Reading only "stats" left every curve empty, and the
# zero-padding step then emitted a complete-looking payload while logging
# "Ripley K/L computed". These tests fail against that behaviour.


def _run_ripley():
    """run_ripley with its module-level collaborators resolved."""
    import importlib.util
    import sys
    from pathlib import Path

    from conftest import SCRIPTS_DIR

    # run_ripley touches several module-level helpers, so load the whole
    # module rather than one AST-extracted function.
    spec = importlib.util.spec_from_file_location(
        "qpcat_spatial_stats_under_test", Path(SCRIPTS_DIR) / SCRIPT
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


def _ripley_adata(n=60, seed=11):
    import anndata as ad
    import pandas as pd

    rs = np.random.RandomState(seed)
    adata = ad.AnnData(X=rs.rand(n, 4).astype(np.float32))
    adata.var_names = ["m0", "m1", "m2", "m3"]
    adata.obsm["spatial"] = rs.rand(n, 2) * 100
    adata.obs["cluster"] = pd.Categorical(
        [str(int(v)) for v in rs.randint(0, 3, size=n)]
    )
    return adata


@requires("squidpy")
@requires("anndata")
def test_ripley_returns_non_empty_curves():
    module = _run_ripley()
    task = _Task()
    module.run_ripley(
        _ripley_adata(),
        task,
        cluster_key="cluster",
        n_permutations=5,
        graph_type="knn",
        persist_plots=False,
    )
    assert "ripley_error" not in task.outputs
    payload = json.loads(task.outputs["ripley"])
    assert payload["radii"], "no radii -- the L payload was not recognised"
    assert any(curve for curve in payload["l_values"]), "all L curves empty"
    # Every curve must be as long as the radius axis, or the plot silently
    # misaligns values against radii.
    for curve in payload["l_values"]:
        assert len(curve) == len(payload["radii"])


@requires("squidpy")
@requires("anndata")
def test_ripley_emits_per_cluster_p_value_curves_not_an_invented_scalar():
    module = _run_ripley()
    task = _Task()
    module.run_ripley(
        _ripley_adata(),
        task,
        cluster_key="cluster",
        n_permutations=5,
        graph_type="knn",
        persist_plots=False,
    )
    payload = json.loads(task.outputs["ripley"])
    curves = payload["p_value_curves"]
    assert curves, "no p-value curves"
    for name, curve in curves.items():
        assert len(curve) == len(payload["radii"])


def test_noise_is_named_noise_and_left_out_of_the_cluster_count():
    """A "Cluster -1" column names a population the reader cannot go look at.

    HDBSCAN's -1 cells are left unclassified in the viewer, so the summary has
    to call them what they are. They also must not inflate
    ``n_clusters_present``: the 2026-08-21 TMA run reported "3 clusters
    present" per core when two of the three were one real cluster and noise.
    """
    build = _summary_builder()
    # One area: 6 clustered cells over two clusters, plus 4 noise cells.
    area_ids = [0] * 10
    labels = [0, 0, 0, 1, 1, 1, -1, -1, -1, -1]
    csv = build(area_ids, ["A-1"], labels, None, ["TMA Core"])
    rows = _rows(csv)
    header = rows[0].split(",")

    assert "Noise_frac" in header and "Noise_count" in header
    assert not any(h.startswith("Cluster -1") for h in header)

    values = dict(zip(header, rows[1].split(",")))
    assert values["n_clusters_present"] == "2"  # noise is not the third
    assert values["Noise_count"] == "4"
    assert values["Noise_frac"] == "0.400000"
