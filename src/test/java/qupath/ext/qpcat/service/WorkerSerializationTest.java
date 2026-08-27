package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QP-CAT tasks must not run concurrently on the shared Python worker.
 *
 * <p>Appose runs one Python thread per task inside ONE interpreter, and
 * {@link ApposeClusteringService} is a singleton, so two workflows started from
 * two dialogs execute in the same process at the same time. Four of the shipped
 * scripts drive matplotlib through pyplot's GLOBAL current-figure state and
 * three call {@code plt.close("all")}, so a concurrent run closes the other's
 * figures or writes the other's figure under its own filename -- with no error,
 * which means the first sign of it is a plot that does not match its result.
 *
 * <p>The regression this guards is not "someone removed the lock" but "someone
 * added a THIRD way to run a task and did not take it". That is a source-shape
 * property, so it is checked against the source.
 */
class WorkerSerializationTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/qupath/ext/qpcat/service/ApposeClusteringService.java");

    private static String source() throws Exception {
        assertThat(Files.exists(SOURCE))
                .as("test must run from the project root so it can read %s", SOURCE)
                .isTrue();
        return Files.readString(SOURCE);
    }

    @Test
    void theWorkerLockIsFairAndReentrant() throws Exception {
        Field f = ApposeClusteringService.class.getDeclaredField("TASK_LOCK");
        f.setAccessible(true);
        Object lock = f.get(null);
        assertThat(lock).isInstanceOf(ReentrantLock.class);
        // Fair: a long clustering run must not starve a queued workflow behind
        // a stream of short tasks.
        assertThat(((ReentrantLock) lock).isFair()).isTrue();
        // Reentrant is implied by the type, and is what stops a task that calls
        // back into the service from deadlocking against itself.
    }

    @Test
    void everyTaskSubmissionIsSerialized() throws Exception {
        String src = source();

        // Each `pythonService.task(...)` is a submission to the shared worker.
        // Count them, and count the guarded runners. Submissions made during
        // initialize() are exempt: initialize() is synchronized and
        // ensureInitialized() completes before any runner takes the lock, so no
        // user workflow can overlap them.
        int submissions = count(src, Pattern.compile("pythonService\\.task\\("));
        int guarded = count(src, Pattern.compile("acquireWorker\\("));
        int inInitialize = 2;   // the registration + verification warm-up tasks

        assertThat(submissions)
                .as("a new pythonService.task(...) call site appeared; it must either "
                        + "run under acquireWorker() or be part of initialize()")
                .isEqualTo(guarded + inInitialize - 1);   // -1: the helper's own definition
    }

    @Test
    void bothPublicRunnersTakeTheLockBeforeSubmitting() throws Exception {
        String src = source();
        for (String runner : new String[]{"public Task runTask(", "public Task runTaskWithListener("}) {
            int at = src.indexOf(runner);
            assertThat(at).as("runner %s not found", runner).isGreaterThan(0);
            // Look only at this method's body, up to the next method declaration.
            int end = src.indexOf("\r\n    public ", at + runner.length());
            if (end < 0) end = src.indexOf("\n    public ", at + runner.length());
            String body = end > at ? src.substring(at, end) : src.substring(at);
            if (!body.contains("pythonService.task(")) continue;   // delegating overload
            assertThat(body.indexOf("acquireWorker("))
                    .as("%s submits to the worker without taking the lock first", runner)
                    .isGreaterThan(0);
            assertThat(body.indexOf("acquireWorker("))
                    .as("%s takes the lock AFTER submitting", runner)
                    .isLessThan(body.indexOf("pythonService.task("));
        }
    }

    @Test
    void theLockIsReleasedOnEveryPathThatTakesIt() throws Exception {
        String src = source();
        assertThat(count(src, Pattern.compile("TASK_LOCK\\.unlock\\(\\)")))
                .as("every acquireWorker() needs a matching unlock in a finally")
                .isEqualTo(count(src, Pattern.compile("acquireWorker\\(scriptName\\)")));
        // The unlocks must be in finally blocks, not on the success path.
        assertThat(src).contains("} finally {");
        for (int i = src.indexOf("TASK_LOCK.unlock()"); i >= 0;
                i = src.indexOf("TASK_LOCK.unlock()", i + 1)) {
            String before = src.substring(Math.max(0, i - 200), i);
            assertThat(before)
                    .as("an unlock at offset %d is not inside a finally block", i)
                    .contains("finally");
        }
    }

    private static int count(String s, Pattern p) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
