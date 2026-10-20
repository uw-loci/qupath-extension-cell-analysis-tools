"""
The last neighbour graph and embedding, kept in the Python worker between runs.

A run that changes only the Leiden resolution clusters the same matrix, so its
neighbour graph and its embedding are the ones the previous run built. Measured
at 300,000 cells, k=50: the graph took 107 s and the clustering 53 s, and at
100,000 cells a seeded UMAP took 60 s. This module keeps the most recent graph
and embedding so the next run can reuse them instead of rebuilding.

Registered in sys.modules when the worker starts (ApposeClusteringService),
because task scripts are exec'd with fresh globals: only a module outlives a
task. The Java side frees it before memory-heavy tasks, when the Run Clustering
dialog is closed, on a project change, and after a failed clustering run.

Rules:
  * ONE slot per kind ("graph", "embedding"); storing replaces.
  * A slot is keyed on a digest of the exact matrix, in cell order, plus every
    parameter its result depends on. Library versions are not in the key: they
    cannot change inside one worker process, and an environment rebuild starts
    a new one.
  * Nothing is kept beyond the byte budget the caller passes; 0 keeps nothing.
  * Callers free a stale slot BEFORE building its replacement, so a miss never
    holds the old and the new at once.
  * Stored arrays are read-only, so code that writes into one fails loudly
    instead of corrupting the next run.

Measurements: claude-reports/2026-10-20_qpcat-knn-graph-caching-research.md.
"""

import gc
import hashlib
import json
import logging
import threading
import time

import numpy as np

logger = logging.getLogger("qpcat.knn_cache")

# Tasks already run one at a time (TASK_LOCK in ApposeClusteringService); the
# lock keeps this module correct without depending on that.
_lock = threading.Lock()
_slots = {}

MB = 1024.0 * 1024.0


def matrix_digest(matrix):
    """Digest of a matrix's shape, dtype and values, in row order.

    :param matrix: 2-D array
    :return: hex string
    """
    arr = np.ascontiguousarray(matrix)
    h = hashlib.blake2b(digest_size=16)
    h.update(repr((arr.shape, arr.dtype.str)).encode("ascii"))
    h.update(arr.data)
    return h.hexdigest()


def make_key(digest, **params):
    """Slot key: the matrix digest plus the parameters, order-independent.

    :param digest: from matrix_digest
    :param params: every parameter the cached result depends on
    :return: key string
    """
    return digest + " " + json.dumps(params, sort_keys=True, default=str)


def nbytes(value):
    """Bytes held by an ndarray or a scipy sparse matrix."""
    if hasattr(value, "indptr"):
        return int(value.data.nbytes + value.indices.nbytes + value.indptr.nbytes)
    return int(np.asarray(value).nbytes)


def _freeze(value):
    if hasattr(value, "indptr"):
        for arr in (value.data, value.indices, value.indptr):
            arr.flags.writeable = False
    else:
        value.flags.writeable = False


def get(kind, key):
    """The slot's value if it was stored under ``key``.

    :param kind: "graph" or "embedding"
    :param key: from make_key
    :return: (value, built, meta) or None; built is the local time it was stored
    """
    with _lock:
        slot = _slots.get(kind)
        if slot is None or slot["key"] != key:
            return None
        return slot["value"], slot["built"], slot["meta"]


def put(kind, value, key, budget_bytes, meta=None, copy=False):
    """Store ``value`` in the slot, replacing what it held, if the budget allows.

    :param kind: "graph" or "embedding"
    :param value: ndarray or scipy CSR matrix; made read-only
    :param key: from make_key
    :param budget_bytes: most this module may hold across all slots
    :param meta: anything to hand back with the value
    :param copy: store a copy, leaving the caller's array writable
    :return: True when stored
    """
    size = nbytes(value)
    with _lock:
        _slots.pop(kind, None)
        others = sum(s["bytes"] for s in _slots.values())
        if budget_bytes <= 0 or others + size > budget_bytes:
            logger.info(
                "Not keeping the %s for reuse: %.1f MB would exceed the %.1f MB allowed",
                kind,
                (others + size) / MB,
                budget_bytes / MB,
            )
            return False
        stored = value.copy() if copy else value
        _freeze(stored)
        _slots[kind] = {
            "key": key,
            "value": stored,
            "bytes": size,
            "built": time.strftime("%H:%M:%S"),
            "meta": meta,
        }
    logger.info("Keeping the %s for reuse (%.1f MB)", kind, size / MB)
    return True


def drop(kind, reason):
    """Free one slot.

    :return: bytes freed
    """
    with _lock:
        slot = _slots.pop(kind, None)
    return _report_freed(0 if slot is None else slot["bytes"], reason)


def keep_only_matrix(digest, reason):
    """Free every slot built from a matrix other than ``digest``.

    :return: bytes freed
    """
    prefix = digest + " "
    with _lock:
        stale = [k for k, s in _slots.items() if not s["key"].startswith(prefix)]
        freed = sum(_slots.pop(k)["bytes"] for k in stale)
    return _report_freed(freed, reason)


def release(reason):
    """Free everything.

    :return: bytes freed
    """
    with _lock:
        freed = sum(s["bytes"] for s in _slots.values())
        _slots.clear()
    return _report_freed(freed, reason)


def held_bytes():
    """Bytes held across all slots."""
    with _lock:
        return sum(s["bytes"] for s in _slots.values())


def _report_freed(freed, reason):
    if freed:
        gc.collect()
        logger.info("Freed %.1f MB kept for reuse (%s)", freed / MB, reason)
    return freed
