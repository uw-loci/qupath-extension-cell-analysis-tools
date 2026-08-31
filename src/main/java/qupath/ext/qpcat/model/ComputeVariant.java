package qupath.ext.qpcat.model;

/**
 * Which bundled Python environment QP-CAT installs: CPU-only or CUDA.
 *
 * <p>A pixi lockfile pins exact package builds, and a CPU build of PyTorch
 * cannot use a GPU. So "no GPU required, but used when present" is not
 * expressible in one environment -- it is either a CPU build that installs
 * everywhere and never accelerates, or a CUDA build that accelerates and
 * <em>cannot install at all</em> on a machine without an NVIDIA GPU. pixi
 * validates the {@code __cuda} virtual package on EVERY install, which is every
 * QuPath launch, so a GPU-pinned environment does not merely run slowly on a
 * CPU-only host: it refuses to start. That is what blocked an HPC deployment
 * (issue #15).
 *
 * <p>Hence two manifests, each with its own lock and its own Appose environment
 * name. Separate names matter: switching must not half-overwrite the other
 * variant's environment, and a user who switches back should find it intact.
 *
 * <p>CPU is the default because it works everywhere. Which operations actually
 * benefit from the GPU is documented in
 * {@code documentation/GPU_ACCELERATION.md} -- the honest summary is that today
 * only the autoencoder uses it.
 *
 * <p>Modelled on {@code CellposeModelFamily} in qupath-extension-cellAPpose,
 * which carries two mutually-exclusive environments the same way.
 */
public enum ComputeVariant {

    /** CPU-only. Installs on any machine; no GPU acceleration. The default. */
    CPU("qupath-qpcat", "pixi.toml", "CPU (works everywhere)"),

    /**
     * CUDA. Accelerates the autoencoder, and REQUIRES an NVIDIA GPU -- the
     * environment cannot be installed without one.
     */
    GPU("qupath-qpcat-gpu", "pixi-gpu.toml", "GPU / CUDA (requires an NVIDIA GPU)");

    private final String envName;
    private final String tomlResource;
    private final String displayLabel;

    ComputeVariant(String envName, String tomlResource, String displayLabel) {
        this.envName = envName;
        this.tomlResource = tomlResource;
        this.displayLabel = displayLabel;
    }

    /** Appose environment name, e.g. {@code qupath-qpcat-gpu}. */
    public String envName() {
        return envName;
    }

    /** Bundled pixi manifest resource filename, e.g. {@code pixi-gpu.toml}. */
    public String tomlResource() {
        return tomlResource;
    }

    /**
     * Bundled lockfile resource, derived from the manifest name (.toml -> .lock)
     * so the two can never be paired wrongly. Staged into the env dir as
     * pixi.lock.
     */
    public String lockResource() {
        return tomlResource.replaceAll("\\.toml$", ".lock");
    }

    public String displayLabel() {
        return displayLabel;
    }

    @Override
    public String toString() {
        return displayLabel;
    }

    /** Parse a stored preference value, falling back to the safe default. */
    public static ComputeVariant fromId(String id) {
        if (id != null) {
            for (ComputeVariant v : values()) {
                if (v.name().equalsIgnoreCase(id.strip())) {
                    return v;
                }
            }
        }
        return CPU;
    }
}
