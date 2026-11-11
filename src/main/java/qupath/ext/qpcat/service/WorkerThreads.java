package qupath.ext.qpcat.service;

import java.util.Collections;
import java.util.Map;

/**
 * The thread-count variable QP-CAT sets on its Python worker.
 *
 * <p>On Windows the worker loads two OpenMP runtimes: MSVC's ({@code vcomp140.dll},
 * scikit-learn) and LLVM's ({@code libomp.dll}, which MKL and PyTorch depend on;
 * conda-forge's {@code llvm-openmp} also ships {@code libiomp5md.dll}, a forwarder
 * to it, so threadpoolctl lists it twice). Compute Thresholds hung in that worker until
 * {@code OMP_NUM_THREADS=1} was set before QuPath started (issue #24); a
 * threadpoolctl limit applied inside the task did not help, so the variable
 * has to be in the process environment when the runtimes load.
 */
public final class WorkerThreads {

    /** The variable every OpenMP runtime and MKL read at load time. */
    public static final String VARIABLE = "OMP_NUM_THREADS";

    private WorkerThreads() {}

    /**
     * The variables to add to the worker's environment.
     *
     * @param limit     whether the user's preference asks for the one-thread limit
     * @param inherited the environment QuPath itself runs in
     * @return {@code OMP_NUM_THREADS=1} when the limit is on and the user has not
     *         set the variable themselves; otherwise empty, so a value the user set
     *         is left alone
     */
    public static Map<String, String> variables(boolean limit, Map<String, String> inherited) {
        if (!limit || inherited.containsKey(VARIABLE)) {
            return Collections.emptyMap();
        }
        return Map.of(VARIABLE, "1");
    }

    /**
     * One line for System Info saying what the worker was started with.
     *
     * @param limit     the preference value
     * @param inherited the environment QuPath runs in
     * @param started   whether the worker has been started
     */
    public static String describe(boolean limit, Map<String, String> inherited, boolean started) {
        if (!started) {
            return "not started";
        }
        String own = inherited.get(VARIABLE);
        if (own != null) {
            return VARIABLE + "=" + own + " inherited from QuPath's environment (preference not applied)";
        }
        if (limit) {
            return VARIABLE + "=1 set at worker start (preference on)";
        }
        return "none (preference off)";
    }
}
