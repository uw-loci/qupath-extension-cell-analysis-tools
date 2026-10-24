"""
Read labels and measurements from an AnnData (.h5ad) file, for importing them
back onto the QuPath cells they came from.

Cells are matched by name: QP-CAT's export names every cell (adata.obs_names)
with its QuPath object id and records the project image in
obs["qupath_image_id"]. scanpy keeps obs_names through filtering, subsetting
and concatenation, so labels added in Python find their cells again. Matching
happens on the Java side; this script only reads.

The file is opened backed (read-only, X left on disk), so only obs and the
chosen obsm arrays are loaded.

Inputs (injected by Appose):
  path: str               -- the .h5ad file
  mode: str               -- "inspect" or "read"
  class_column: str       -- read: obs column to import as the classification, or ""
  number_columns: list    -- read: numeric obs columns to import as measurements
  embedding_keys: list    -- read: obsm keys to import as measurements, one per axis

Outputs (via task.outputs):
  inspect: summary_json (str) -- see summarize()
  read:    obs_names (list[str]), image_ids (list[str] or None),
           class_codes (NDArray int32, -1 = no value), class_names (list[str]),
           class_colors (list[str] "#rrggbb", or None),
           values (NDArray float64, n_cells x n_values), value_names (list[str])
"""

import json
import logging

import numpy as np
import pandas as pd

logger = logging.getLogger("qpcat.read_anndata")

IMAGE_ID_COLUMN = "qupath_image_id"
IMAGE_NAME_COLUMN = "qupath_image"

# A column with more distinct values than this is not offered as a
# classification: it would create that many classes.
MAX_CLASSES = 500


def column_kind(series):
    """How an obs column can be imported.

    :param series: a pandas Series
    :return: (can_be_class, can_be_number)
    """
    dtype = series.dtype
    if isinstance(dtype, pd.CategoricalDtype):
        return True, False
    if pd.api.types.is_bool_dtype(dtype):
        return True, False
    if pd.api.types.is_integer_dtype(dtype):
        return True, True
    if pd.api.types.is_float_dtype(dtype):
        return False, True
    if pd.api.types.is_object_dtype(dtype) or pd.api.types.is_string_dtype(dtype):
        return True, False
    return False, False


def summarize(obs, obsm_shapes, n_cells):
    """What the file holds, for the import dialog.

    :param obs: the obs DataFrame
    :param obsm_shapes: {key: shape} of the obsm arrays
    :param n_cells: cell count
    :return: dict (JSON-serializable)
    """
    columns = []
    for name in obs.columns:
        if name in (IMAGE_ID_COLUMN, IMAGE_NAME_COLUMN):
            continue
        series = obs[name]
        as_class, as_number = column_kind(series)
        n_values = int(series.nunique(dropna=True))
        if as_class and n_values > MAX_CLASSES:
            as_class = False
        if as_class or as_number:
            columns.append(
                {
                    "name": str(name),
                    "as_class": bool(as_class),
                    "as_number": bool(as_number),
                    "n_values": n_values,
                    "n_missing": int(series.isna().sum()),
                }
            )
    embeddings = [
        {"key": str(k), "n_dims": int(s[1])}
        for k, s in obsm_shapes.items()
        if len(s) == 2 and 1 <= s[1] <= 10
    ]
    images = []
    if IMAGE_ID_COLUMN in obs.columns:
        ids = obs[IMAGE_ID_COLUMN].astype(str)
        names = (
            obs[IMAGE_NAME_COLUMN].astype(str)
            if IMAGE_NAME_COLUMN in obs.columns
            else ids
        )
        for image_id, group in pd.DataFrame({"id": ids, "name": names}).groupby(
            "id", sort=False
        ):
            images.append(
                {
                    "id": str(image_id),
                    "name": str(group["name"].iloc[0]),
                    "n_cells": int(len(group)),
                }
            )
    return {
        "n_cells": int(n_cells),
        "columns": columns,
        "embeddings": embeddings,
        "images": images,
        "has_image_ids": IMAGE_ID_COLUMN in obs.columns,
    }


def class_codes(series, colors=None):
    """Per-cell code into a list of class names; -1 where the cell has no value.

    :param series: the obs column
    :param colors: scanpy's uns["<column>_colors"], one per category, or None
    :return: (codes int32 array, names list, colors list or None)
    """
    if isinstance(series.dtype, pd.CategoricalDtype):
        cat = series
    else:
        cat = series.astype("object").where(series.notna()).astype("category")
    codes = np.asarray(cat.cat.codes, dtype=np.int32)
    names = [str(c) for c in cat.cat.categories]
    used = None
    if colors is not None and len(colors) == len(names):
        used = [str(c)[:7] for c in colors]
    return codes, names, used


def value_columns(obs, number_columns, obsm, embedding_keys):
    """The numeric values to import, as one matrix with a name per column.

    :return: (float64 array n_cells x n_values, names)
    """
    n_cells = len(obs)
    blocks, names = [], []
    for name in number_columns:
        blocks.append(np.asarray(obs[name], dtype=np.float64).reshape(n_cells, 1))
        names.append(str(name))
    for key in embedding_keys:
        arr = np.asarray(obsm[key], dtype=np.float64)
        blocks.append(arr.reshape(n_cells, -1))
        names.extend("%s %d" % (key, d + 1) for d in range(blocks[-1].shape[1]))
    if not blocks:
        return np.zeros((n_cells, 0), dtype=np.float64), names
    return np.hstack(blocks), names


# ---------------------------------------------------------------------------
# Entry point (Appose injects the globals below)
# ---------------------------------------------------------------------------

try:
    _path = path  # noqa: F821
except NameError:  # pragma: no cover - loaded by the tests
    _path = None

if _path is not None:
    import anndata as ad
    from appose import NDArray as PyNDArray

    task.update("Reading %s..." % _path)  # noqa: F821
    _adata = ad.read_h5ad(_path, backed="r")
    try:
        _obs = _adata.obs
        _n = int(_adata.n_obs)
        if mode == "inspect":  # noqa: F821
            _summary = summarize(
                _obs, {k: _adata.obsm[k].shape for k in _adata.obsm.keys()}, _n
            )
            task.outputs["summary_json"] = json.dumps(_summary)  # noqa: F821
            logger.info(
                "AnnData file: %d cells, %d importable obs columns, %d embeddings",
                _n,
                len(_summary["columns"]),
                len(_summary["embeddings"]),
            )
        else:
            _names = [str(x) for x in _adata.obs_names]
            if len(set(_names)) != len(_names):
                raise ValueError(
                    "The file's cell names (obs_names) are not unique, so they cannot "
                    "identify QuPath objects. Export from QP-CAT names every cell "
                    "with its object id; keep those names in Python."
                )
            task.outputs["obs_names"] = _names  # noqa: F821
            task.outputs["image_ids"] = (  # noqa: F821
                [str(x) for x in _obs[IMAGE_ID_COLUMN]]
                if IMAGE_ID_COLUMN in _obs.columns
                else None
            )
            _codes = np.full(_n, -1, dtype=np.int32)
            _cnames, _ccolors = [], None
            if class_column:  # noqa: F821
                _codes, _cnames, _ccolors = class_codes(
                    _obs[class_column],  # noqa: F821
                    _adata.uns.get("%s_colors" % class_column),  # noqa: F821
                )
            _codes_nd = PyNDArray(dtype="int32", shape=[_n])
            np.copyto(_codes_nd.ndarray(), _codes)
            task.outputs["class_codes"] = _codes_nd  # noqa: F821
            task.outputs["class_names"] = _cnames  # noqa: F821
            task.outputs["class_colors"] = _ccolors  # noqa: F821
            _vals, _vnames = value_columns(
                _obs,
                list(number_columns),  # noqa: F821
                _adata.obsm,
                list(embedding_keys),  # noqa: F821
            )
            _vals_nd = PyNDArray(dtype="float64", shape=[_n, max(1, _vals.shape[1])])
            if _vals.shape[1]:
                np.copyto(_vals_nd.ndarray(), _vals)
            task.outputs["values"] = _vals_nd  # noqa: F821
            task.outputs["value_names"] = _vnames  # noqa: F821
            logger.info(
                "Read %d cells: class column %r, %d value column(s)",
                _n,
                class_column,  # noqa: F821
                len(_vnames),
            )
    finally:
        _adata.file.close()
