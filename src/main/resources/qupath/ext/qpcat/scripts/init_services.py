"""
Appose worker initialization script for QP-CAT.

Called once via pythonService.init() when the worker process starts.
Sets up persistent globals that remain available across all task() calls.

CRITICAL: All output must go to sys.stderr, NOT sys.stdout.
Appose uses stdout for its JSON-based IPC protocol.
"""
import sys
import os
import logging
import threading
import time

logging.basicConfig(
    level=logging.INFO,
    stream=sys.stderr,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)

logger = logging.getLogger("qpcat.appose")

# --- Environment version ---
# This version MUST match build.gradle.kts. When the JAR-bundled pixi.toml
# or scripts change in a way that requires a new environment, bump this version.
# The Java side checks this after init to detect stale environments that
# rebuilt from pixi.toml changes but may still have outdated pip packages.
_ENVIRONMENT_VERSION = "0.2.0"

try:
    # Set non-interactive backend before any matplotlib import (scanpy pulls it in)
    import matplotlib
    matplotlib.use('Agg')

    import numpy
    import pandas
    import sklearn
    import umap
    import leidenalg
    import igraph
    import scanpy
    import anndata
    import squidpy
    import harmonypy
    import banksy

    logger.info("QP-CAT environment v%s", _ENVIRONMENT_VERSION)
    logger.info("  scikit-learn: %s", sklearn.__version__)
    logger.info("  scanpy: %s", scanpy.__version__)
    logger.info("  umap-learn: %s", umap.__version__)
    logger.info("  leidenalg: %s", leidenalg.__version__)
    logger.info("  anndata: %s", anndata.__version__)
    logger.info("  squidpy: %s", squidpy.__version__)
    logger.info("  harmonypy: available")
    logger.info("  pybanksy: available")

    # Check for new optional dependencies (may not be present in older environments)
    _optional_packages = {
        "torch": "PyTorch",
        "timm": "timm",
        "open_clip": "open-clip-torch",
    }
    for pkg, display in _optional_packages.items():
        try:
            mod = __import__(pkg)
            ver = getattr(mod, "__version__", "available")
            logger.info("  %s: %s", display, ver)
        except ImportError:
            logger.info("  %s: NOT INSTALLED (feature extraction/zero-shot unavailable)", display)

    init_error = None

except Exception as e:
    logger.error("Failed to initialize QP-CAT: %s", e)
    init_error = str(e)


# --- Parent process watcher ---
def _parent_alive(pid):
    """Check if a process with the given PID is still running."""
    if sys.platform == 'win32':
        import ctypes
        kernel32 = ctypes.windll.kernel32
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if handle:
            kernel32.CloseHandle(handle)
            return True
        return False
    else:
        try:
            os.kill(pid, 0)
            return True
        except PermissionError:
            return True
        except OSError:
            return False


def _watch_parent():
    """Exit if parent process (Java/QuPath) dies."""
    ppid = os.getppid()
    if ppid <= 1:
        return
    logger.info("Parent process watcher started (parent PID: %d)", ppid)
    while True:
        time.sleep(3)
        try:
            current_ppid = os.getppid()
            if current_ppid != ppid:
                logger.warning("Parent process changed (%d -> %d), exiting",
                               ppid, current_ppid)
                os._exit(1)
            if not _parent_alive(ppid):
                logger.warning("Parent process %d no longer exists, exiting",
                               ppid)
                os._exit(1)
        except Exception as e:
            logger.debug("Parent watcher check error: %s", e)


_parent_watcher = threading.Thread(target=_watch_parent, daemon=True)
_parent_watcher.start()
