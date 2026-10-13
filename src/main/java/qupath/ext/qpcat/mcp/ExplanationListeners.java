package qupath.ext.qpcat.mcp;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registry that lets an open results panel learn when explanations were
 * written to its saved result from elsewhere (the MCP tools). Consumers run
 * on the FX thread.
 */
public final class ExplanationListeners {

    private static final Logger logger = LoggerFactory.getLogger(ExplanationListeners.class);

    private record Entry(String resultName, Consumer<LlmExplanationsBundle> onWrite) { }

    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    private ExplanationListeners() { }

    /**
     * Registers a consumer for one saved result.
     *
     * @param resultName saved result name; must not be null
     * @param onWrite    receives the bundle that was written
     * @return the action that unregisters this consumer
     */
    public static Runnable register(String resultName, Consumer<LlmExplanationsBundle> onWrite) {
        Objects.requireNonNull(resultName, "resultName");
        Objects.requireNonNull(onWrite, "onWrite");
        Entry entry = new Entry(resultName, onWrite);
        ENTRIES.add(entry);
        return () -> ENTRIES.remove(entry);
    }

    /**
     * Delivers {@code bundle} to every consumer registered for {@code resultName}.
     *
     * @return the number of consumers notified
     */
    public static int notify(String resultName, LlmExplanationsBundle bundle) {
        int n = 0;
        for (Entry e : ENTRIES) {
            if (e.resultName().equals(resultName)) {
                n++;
                runOnFx(() -> {
                    try {
                        e.onWrite().accept(bundle);
                    } catch (RuntimeException ex) {
                        logger.warn("Explanation listener failed: {}", ex.toString());
                    }
                });
            }
        }
        return n;
    }

    /** @return how many consumers are registered for {@code resultName} */
    public static int count(String resultName) {
        int n = 0;
        for (Entry e : ENTRIES) {
            if (e.resultName().equals(resultName)) n++;
        }
        return n;
    }

    /** Runs on the FX thread; inline when already there or when no toolkit is running. */
    static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        try {
            Platform.runLater(r);
        } catch (IllegalStateException noToolkit) {
            r.run();
        }
    }
}
