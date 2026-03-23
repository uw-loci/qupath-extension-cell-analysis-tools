"""
Collects Python environment information for the System Info dialog.
"""
import sys
import platform
import logging

logger = logging.getLogger("qpcat.system_info")

lines = []
lines.append("=== Python Environment ===")
lines.append("Python: %s" % sys.version)
lines.append("Platform: %s" % platform.platform())
lines.append("")

lines.append("=== Package Versions ===")
packages = [
    "numpy", "pandas", "scipy", "scikit-learn",
    "scanpy", "anndata", "umap-learn", "leidenalg",
    "igraph", "numba", "matplotlib", "seaborn"
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

info_text = "\n".join(lines)
task.outputs["info_text"] = info_text
logger.info("System info collected")
