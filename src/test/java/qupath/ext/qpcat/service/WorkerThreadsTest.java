package qupath.ext.qpcat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerThreadsTest {

    @Test
    void limitOnSetsOneThread() {
        assertEquals(Map.of("OMP_NUM_THREADS", "1"), WorkerThreads.variables(true, Map.of()));
    }

    @Test
    void limitOffSetsNothing() {
        assertTrue(WorkerThreads.variables(false, Map.of()).isEmpty());
    }

    @Test
    void userValueIsLeftAlone() {
        assertTrue(WorkerThreads.variables(true, Map.of("OMP_NUM_THREADS", "4")).isEmpty());
    }

    @Test
    void describeNamesEachCase() {
        assertEquals("not started", WorkerThreads.describe(true, Map.of(), false));
        assertEquals("OMP_NUM_THREADS=1 set at worker start (preference on)",
                WorkerThreads.describe(true, Map.of(), true));
        assertEquals("none (preference off)", WorkerThreads.describe(false, Map.of(), true));
        assertEquals("OMP_NUM_THREADS=4 inherited from QuPath's environment (preference not applied)",
                WorkerThreads.describe(true, Map.of("OMP_NUM_THREADS", "4"), true));
    }
}
