package qupath.ext.qpcat.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A small multicast registry for "the selection changed" callbacks.
 * <p>
 * {@link MeasurementSelectionPane} has a single {@code onSelectionChanged} slot
 * that the clustering dialog owns -- it is what refreshes the pre-flight caution
 * and the run-cost line. When the measurement list is popped out into its own
 * window, that window needs notifications too, for the live count on its Accept
 * button. Taking the slot would have stopped the pre-flight updating for the rest
 * of the session with nothing on screen to say so.
 * <p>
 * Separated from the pane so it can be tested without a JavaFX toolkit: building
 * the pane constructs a {@code TextField}, which needs one.
 */
final class SelectionListeners {

    private final List<Runnable> listeners = new ArrayList<>();

    /** Register {@code r}; null is ignored so callers need no guard. */
    void add(Runnable r) {
        if (r != null) {
            listeners.add(r);
        }
    }

    /** Deregister the same instance passed to {@link #add(Runnable)}. */
    void remove(Runnable r) {
        listeners.remove(r);
    }

    int size() {
        return listeners.size();
    }

    /**
     * Run every listener, in registration order.
     * <p>
     * A listener that throws is swallowed and the rest still run. These fire on
     * the FX thread during a selection change: one misbehaving window must not
     * take the pre-flight down with it, and there is no user action that could
     * respond to the exception anyway. Iterates a copy, so a listener that
     * deregisters itself cannot raise ConcurrentModificationException.
     */
    void fire() {
        for (Runnable r : new ArrayList<>(listeners)) {
            try {
                r.run();
            } catch (Exception ignore) {
                // UI sink -- see javadoc.
            }
        }
    }
}
