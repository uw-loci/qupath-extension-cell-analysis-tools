package qupath.ext.qpcat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.ui.SavedDataTracker.Decision;
import qupath.ext.qpcat.ui.SavedDataTracker.State;
import qupath.ext.qpcat.ui.SavedDataTracker.Status;

/** The live-versus-saved decision table and the banner's wording. */
class SavedDataTrackerTest {

    private static final long T0 = 1_000_000L;

    @Test
    void untouchedImageIsInSync() {
        Decision d = SavedDataTracker.decide(false, false, T0, T0);
        assertEquals(State.IN_SYNC, d.state());
        assertEquals(T0, d.baseline());
        assertFalse(d.mirrorDirty());
    }

    @Test
    void editsInTheViewerAreLiveAhead() {
        Decision d = SavedDataTracker.decide(true, true, T0, T0);
        assertEquals(State.LIVE_AHEAD, d.state());
        assertTrue(d.mirrorDirty());
    }

    @Test
    void aScriptSavingTheEntryIsSavedAhead() {
        // Nothing changed in the viewer; the file is newer than when the image was opened.
        Decision d = SavedDataTracker.decide(false, false, T0, T0 + 5000);
        assertEquals(State.SAVED_AHEAD, d.state());
        assertEquals(T0, d.baseline(), "the baseline stays until the user reloads or ignores");
    }

    @Test
    void bothSidesChanged() {
        Decision d = SavedDataTracker.decide(true, true, T0, T0 + 5000);
        assertEquals(State.BOTH, d.state());
    }

    @Test
    void savingTheLiveImageMovesTheBaseline() {
        // The viewer was edited (mirror saw it), then saved: QuPath's flag is clear and the
        // file is newer. That is the user's own save, not another writer.
        Decision d = SavedDataTracker.decide(false, true, T0, T0 + 5000);
        assertEquals(State.IN_SYNC, d.state());
        assertEquals(T0 + 5000, d.baseline());
        assertFalse(d.mirrorDirty());
    }

    @Test
    void quPathFlagAloneIsNotLiveAhead() {
        // QuPath marks a freshly opened image as changed (image type, metadata); without a
        // hierarchy change seen here, that is in sync.
        Decision d = SavedDataTracker.decide(true, false, T0, T0);
        assertEquals(State.IN_SYNC, d.state());
        assertFalse(d.mirrorDirty());
        // and with a newer file it is the saved copy that moved, not the viewer
        assertEquals(State.SAVED_AHEAD, SavedDataTracker.decide(true, false, T0, T0 + 1).state());
    }

    @Test
    void messagesNameTheCaseAndStaySilentInSync() {
        assertNull(SavedDataBanner.message(new Status(State.IN_SYNC, T0, "a.tif")));
        assertNull(SavedDataBanner.message(new Status(State.UNTRACKED, 0, null)));
        String saved = SavedDataBanner.message(new Status(State.SAVED_AHEAD, T0, "a.tif"));
        assertTrue(saved.contains("saved copy of this image changed at"));
        assertTrue(saved.contains("script run for the project"));
        String both = SavedDataBanner.message(new Status(State.BOTH, T0, "a.tif"));
        assertTrue(both.contains("unsaved edits of its own"));
        String live = SavedDataBanner.message(new Status(State.LIVE_AHEAD, 0, "a.tif"));
        assertTrue(live.contains("unsaved edits"));
    }
}
