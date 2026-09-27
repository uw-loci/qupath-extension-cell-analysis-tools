package qupath.ext.qpcat.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry that lets the popped-out measurement window be notified without
 * displacing the clustering dialog.
 *
 * <p>The pane has ONE {@code onSelectionChanged} slot and the dialog owns it --
 * that listener refreshes the pre-flight caution and the run-cost line. Had the
 * pop-out taken the slot, the pre-flight would have stopped updating for the
 * rest of the session with nothing on screen to say so.
 */
class SelectionListenersTest {

    @Test
    void everyListenerRunsInRegistrationOrder() {
        SelectionListeners l = new SelectionListeners();
        List<String> order = new ArrayList<>();
        l.add(() -> order.add("dialog"));
        l.add(() -> order.add("popout"));
        l.fire();
        assertThat(order).containsExactly("dialog", "popout");
    }

    @Test
    void removingOneLeavesTheOthers() {
        SelectionListeners l = new SelectionListeners();
        AtomicInteger kept = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();
        Runnable r = dropped::incrementAndGet;
        l.add(kept::incrementAndGet);
        l.add(r);
        l.remove(r);
        l.fire();
        assertThat(dropped.get()).as("a closed window stops being notified").isZero();
        assertThat(kept.get()).isOne();
    }

    @Test
    void aThrowingListenerDoesNotStopTheRest() {
        // These fire on the FX thread during a selection change. One bad window
        // must not take the pre-flight down with it.
        SelectionListeners l = new SelectionListeners();
        AtomicInteger after = new AtomicInteger();
        l.add(() -> {
            throw new IllegalStateException("boom");
        });
        l.add(after::incrementAndGet);
        l.fire();
        assertThat(after.get()).isOne();
    }

    @Test
    void aListenerMayDeregisterItselfWhileFiring() {
        // The pop-out's Accept button removes its listener when the window
        // closes, and closing can be what the listener itself triggers.
        SelectionListeners l = new SelectionListeners();
        AtomicInteger runs = new AtomicInteger();
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            runs.incrementAndGet();
            l.remove(self[0]);
        };
        l.add(self[0]);
        l.fire();
        l.fire();
        assertThat(runs.get()).as("ran once, then deregistered without throwing").isOne();
    }

    @Test
    void nullIsIgnoredSoCallersNeedNoGuard() {
        SelectionListeners l = new SelectionListeners();
        l.add(null);
        assertThat(l.size()).isZero();
        l.fire();
    }
}
