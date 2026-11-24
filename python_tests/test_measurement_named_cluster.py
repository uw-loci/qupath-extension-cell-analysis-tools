"""H25: a measurement literally named "cluster" is the user's data, not a label column.

run_clustering.py wrote the labels into the measurement frame as df_norm["cluster"],
overwriting such a measurement; the per-cluster means then had one column too few
and the AnnData build failed after the embedding and the clustering had run. The
labels now stay outside the frame, and the AnnData obs key moves aside so scanpy
does not refuse the plots for an ambiguous key.
"""

import json
import tempfile

import numpy as np

from conftest import SCRIPTS_DIR, load_script_symbol, requires
from test_clean_data_bit_identical import fixed_matrix, run_full_clustering

internal_cluster_key = load_script_symbol("run_clustering.py", "internal_cluster_key")


def test_the_obs_key_avoids_every_measurement_name():
    assert internal_cluster_key(["CD3", "CD8"]) == "cluster"
    assert internal_cluster_key(["cluster", "CD8"]) == "cluster (QP-CAT 1)"
    assert (
        internal_cluster_key(["cluster", "cluster (QP-CAT 1)"]) == "cluster (QP-CAT 2)"
    )


@requires("scanpy")
@requires("appose")
def test_a_measurement_named_cluster_survives_a_full_run_with_plots():
    data = fixed_matrix()
    names = ["cluster"] + ["Cell: M%d mean" % i for i in range(1, data.shape[1])]
    out = run_full_clustering(
        SCRIPTS_DIR,
        data,
        "zscore",
        names=names,
        embedding_method="umap",
        generate_plots=True,
        output_dir=tempfile.mkdtemp(),
    )
    stats = out["cluster_stats"].ndarray()
    assert stats.shape == (3, len(names))
    # Column 0 is the user's "cluster" measurement: its z-scored cluster means
    # are not the label values 0, 1, 2.
    assert not np.allclose(np.sort(stats[:, 0]), [0.0, 1.0, 2.0])
    plots = json.loads(out["plot_paths"])
    for key in ("dotplot", "matrixplot", "stacked_violin", "embedding"):
        assert key in plots, (key, out.get("omitted_outputs"))
    ranked = {
        m["name"] for ms in json.loads(out["marker_rankings"]).values() for m in ms
    }
    assert ranked <= set(names)
