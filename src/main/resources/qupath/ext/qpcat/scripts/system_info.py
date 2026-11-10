"""
Collects Python environment information for the System Info dialog.
"""

import os
import sys
import platform
import logging

logger = logging.getLogger("qpcat.system_info")

THREAD_ENV_VARS = (
    "OMP_NUM_THREADS",
    "MKL_NUM_THREADS",
    "OPENBLAS_NUM_THREADS",
    "NUMBA_NUM_THREADS",
)


def thread_pool_lines(pools, environ):
    """Lines for System Info: each loaded BLAS / OpenMP library with its thread
    count, then the thread-count variables. Written for issue #24, where a
    Windows worker hung until OMP_NUM_THREADS=1; this is what a report needs.

    :param pools: threadpoolctl.threadpool_info(), or None when it is not installed
    :param environ: the worker's environment mapping
    """
    out = ["=== Thread pools (threadpoolctl) ==="]
    if pools is None:
        out.append("  threadpoolctl not installed")
    elif not pools:
        out.append("  no BLAS or OpenMP library loaded")
    for pool in pools or []:
        layer = pool.get("threading_layer")
        out.append(
            "  %s: %s%s, %s threads, %s"
            % (
                pool.get("user_api"),
                pool.get("internal_api"),
                " (%s)" % layer if layer else "",
                pool.get("num_threads"),
                pool.get("filepath"),
            )
        )
    out.append("=== Thread environment ===")
    for name in THREAD_ENV_VARS:
        out.append("  %s: %s" % (name, environ.get(name, "(unset)")))
    return out


lines = []
lines.append("=== Python Environment ===")
lines.append("Python: %s" % sys.version)
lines.append("Platform: %s" % platform.platform())
lines.append("")

lines.append("=== Package Versions ===")
packages = [
    "numpy",
    "pandas",
    "scipy",
    "scikit-learn",
    "scanpy",
    "anndata",
    "umap-learn",
    "leidenalg",
    "igraph",
    "numba",
    "matplotlib",
    "seaborn",
]

for pkg_name in packages:
    try:
        # Handle packages with different import names
        import_name = pkg_name.replace("-", "_")
        if pkg_name == "scikit-learn":
            import_name = "sklearn"
        elif pkg_name == "umap-learn":
            import_name = "umap"

        mod = __import__(import_name)
        version = getattr(mod, "__version__", "unknown")
        lines.append("  %s: %s" % (pkg_name, version))
    except ImportError:
        lines.append("  %s: NOT INSTALLED" % pkg_name)
    except Exception as e:
        lines.append("  %s: error (%s)" % (pkg_name, e))

lines.append("")
try:
    import threadpoolctl

    _pools = threadpoolctl.threadpool_info()
except ImportError:
    _pools = None
lines.extend(thread_pool_lines(_pools, os.environ))

info_text = "\n".join(lines)
task.outputs["info_text"] = info_text
logger.info("System info collected")
