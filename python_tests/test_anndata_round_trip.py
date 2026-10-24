"""AnnData export -> analysis in scanpy -> import (export_anndata.py, read_anndata.py).

The import matches cells by name, so the contract is: the export names every
cell with its QuPath object id, and those names survive what people do in
Python (subsetting, adding obs columns, scanpy's own clustering and colors).
"""

import json
import sys
import types
import uuid

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


class _Nd:
    """Appose's NDArray, as both an input and a script-made output."""

    def __init__(self, array=None, dtype=None, shape=None):
        self._array = array if array is not None else np.zeros(shape, dtype=dtype)

    def ndarray(self):
        return self._array


@pytest.fixture(autouse=True)
def appose_stub(monkeypatch):
    stub = types.ModuleType("appose")
    stub.NDArray = _Nd
    monkeypatch.setitem(sys.modules, "appose", stub)


def run(script, **inputs):
    task = _Task()
    namespace = {"task": task, **inputs}
    source = (SCRIPTS_DIR / script).read_text(encoding="utf-8")
    exec(compile(source, script, "exec"), namespace)
    return task.outputs


def save(adata, path):
    """Write as a notebook on pandas 2 would.

    This environment has pandas 3, whose string dtype the locked anndata cannot
    write (see export_anndata.py), so every string goes back to object first.
    """
    import pandas as pd

    with pd.option_context("future.infer_string", False):
        adata.obs_names = pd.Index(list(map(str, adata.obs_names)), dtype=object)
        adata.var_names = pd.Index(list(map(str, adata.var_names)), dtype=object)
        for col in list(adata.obs.columns):
            series = adata.obs[col]
            if isinstance(series.dtype, pd.CategoricalDtype):
                cats = series.cat.categories
                if not pd.api.types.is_numeric_dtype(cats.dtype):
                    adata.obs[col] = pd.Categorical(
                        series.astype(object).to_numpy(),
                        categories=pd.Index(list(map(str, cats)), dtype=object),
                    )
            elif not pd.api.types.is_numeric_dtype(series.dtype):
                adata.obs[col] = series.astype(object).to_numpy()
        adata.write_h5ad(path)


def export(tmp_path, n=300, seed=0, image_id="7", image_name="tme_07.tif"):
    rng = np.random.default_rng(seed)
    ids = [str(uuid.UUID(int=int(rng.integers(1, 2**62)))) for _ in range(n)]
    path = tmp_path / ("export_%s.h5ad" % image_id)
    run(
        "export_anndata.py",
        measurements=_Nd(rng.normal(size=(n, 4))),
        marker_names=["CD3", "CD8", "CD20", "PanCK"],
        output_path=str(path),
        object_ids=ids,
        image_id=image_id,
        image_name=image_name,
        spatial_coords=_Nd(rng.uniform(0, 1000, (n, 2))),
    )
    return path, ids


@requires("anndata")
def test_the_export_names_every_cell_by_its_object_id(tmp_path):
    import anndata as ad

    path, ids = export(tmp_path)
    adata = ad.read_h5ad(path)
    assert list(adata.obs_names) == ids
    assert set(adata.obs["qupath_image_id"]) == {"7"}
    assert set(adata.obs["qupath_image"]) == {"tme_07.tif"}


@requires("anndata")
def test_inspect_lists_what_can_be_imported(tmp_path):
    import anndata as ad

    path, _ = export(tmp_path)
    adata = ad.read_h5ad(path)
    adata.obs["gate"] = np.where(adata.X[:, 0] > 0, "CD3+", "CD3-")
    adata.obs["score"] = adata.X[:, 1] * 2.0
    adata.obs["level"] = (adata.X[:, 2] > 0).astype(int)
    adata.obsm["X_umap"] = adata.X[:, :2].copy()
    save(adata, path)

    summary = json.loads(
        run("read_anndata.py", path=str(path), mode="inspect")["summary_json"]
    )
    cols = {c["name"]: c for c in summary["columns"]}
    assert cols["gate"]["as_class"] and not cols["gate"]["as_number"]
    assert cols["score"]["as_number"] and not cols["score"]["as_class"]
    assert cols["level"]["as_class"] and cols["level"]["as_number"]
    assert "qupath_image_id" not in cols
    assert {e["key"]: e["n_dims"] for e in summary["embeddings"]}["X_umap"] == 2
    assert summary["images"] == [{"id": "7", "name": "tme_07.tif", "n_cells": 300}]
    assert summary["has_image_ids"]


@requires("scanpy")
def test_labels_made_in_scanpy_come_back_for_the_same_cells(tmp_path):
    import anndata as ad
    import scanpy as sc

    path, ids = export(tmp_path, n=400)
    adata = ad.read_h5ad(path)
    # A typical back-gating session: drop some cells, cluster, color, save.
    adata = adata[adata.X[:, 3] < 1.5].copy()
    sc.pp.neighbors(adata, n_neighbors=10, use_rep="X")
    sc.tl.leiden(adata, resolution=0.5, flavor="igraph", n_iterations=2)
    sc.pl._utils._set_default_colors_for_categorical_obs(adata, "leiden")
    adata.obs.loc[adata.obs_names[:5], "leiden"] = np.nan
    save(adata, path)

    out = run(
        "read_anndata.py",
        path=str(path),
        mode="read",
        class_column="leiden",
        number_columns=[],
        embedding_keys=["spatial"],
    )
    assert out["obs_names"] == list(adata.obs_names)
    assert set(out["obs_names"]) <= set(ids)
    codes = out["class_codes"].ndarray()
    names = out["class_names"]
    assert (codes[:5] == -1).all()
    expected = adata.obs["leiden"].astype(str).to_numpy()
    for i in range(5, len(codes)):
        assert names[codes[i]] == expected[i]
    assert len(out["class_colors"]) == len(names)
    assert all(c.startswith("#") and len(c) == 7 for c in out["class_colors"])
    assert out["value_names"] == ["spatial 1", "spatial 2"]
    assert np.allclose(out["values"].ndarray(), adata.obsm["spatial"])


@requires("anndata")
def test_concatenated_images_keep_their_names_and_image_ids(tmp_path):
    import anndata as ad

    a, ids_a = export(tmp_path, n=50, seed=1, image_id="1", image_name="a.tif")
    b, ids_b = export(tmp_path, n=60, seed=2, image_id="2", image_name="b.tif")
    both = ad.concat([ad.read_h5ad(a), ad.read_h5ad(b)])
    both.obs["score"] = np.arange(110, dtype=float)
    path = tmp_path / "both.h5ad"
    save(both, path)

    out = run(
        "read_anndata.py",
        path=str(path),
        mode="read",
        class_column="",
        number_columns=["score"],
        embedding_keys=[],
    )
    assert out["obs_names"] == ids_a + ids_b
    assert out["image_ids"] == ["1"] * 50 + ["2"] * 60
    assert out["class_names"] == []
    assert (out["class_codes"].ndarray() == -1).all()
    assert out["values"].ndarray()[:, 0].tolist() == list(range(110))


@requires("anndata")
def test_repeated_cell_names_are_refused(tmp_path):
    import anndata as ad

    adata = ad.AnnData(X=np.zeros((4, 1)))
    adata.obs_names = ["a", "a", "b", "c"]
    path = tmp_path / "dup.h5ad"
    save(adata, path)
    with pytest.raises(ValueError, match="not unique"):
        run(
            "read_anndata.py",
            path=str(path),
            mode="read",
            class_column="",
            number_columns=[],
            embedding_keys=[],
        )


def test_too_many_values_are_not_offered_as_a_classification():
    import pandas as pd

    summarize = load_script_symbol(
        "read_anndata.py",
        "summarize",
        {
            "pd": pd,
            "column_kind": load_script_symbol(
                "read_anndata.py", "column_kind", {"pd": pd}
            ),
            "IMAGE_ID_COLUMN": "qupath_image_id",
            "IMAGE_NAME_COLUMN": "qupath_image",
            "MAX_CLASSES": 500,
        },
    )
    obs = pd.DataFrame({"id_like": [str(i) for i in range(600)]})
    summary = summarize(obs, {}, 600)
    assert summary["columns"] == []
