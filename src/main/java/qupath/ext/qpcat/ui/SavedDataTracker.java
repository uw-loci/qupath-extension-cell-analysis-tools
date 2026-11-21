package qupath.ext.qpcat.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Knows whether the open image and its saved copy in the project still agree.
 *
 * <p>QuPath does not detect this itself: the script editor warns before a project run
 * and offers a refresh after it, and nothing watches the data file. Two directions:</p>
 * <ul>
 * <li><b>Live ahead of saved</b>: a hierarchy change since the image was opened or last saved,
 *     seen by this class's own listener. QuPath's {@link ImageData#isChanged()} is not usable
 *     for this on its own (it is already true after an image opens); it serves to recognize a
 *     save, which clears it.</li>
 * <li><b>Saved ahead of live</b>: the entry's {@code data.qpdata} is newer than the baseline
 *     recorded when the image was opened (a script run for the project, another QuPath). A
 *     save of the live image also makes the file newer; it is told apart because the
 *     hierarchy changed first (this class mirrors QuPath's change flag with its own listener)
 *     and QuPath's flag is clear afterwards, so the baseline moves to that save. A manual save
 *     of an UNCHANGED image is the one case that reads as "saved by something else"; the
 *     banner's Ignore button is the answer.</li>
 * </ul>
 *
 * <p>One {@code stat} per check, on demand (dialog open, Run, window focus); nothing polls.
 * A {@code WatchService} would be continuous and unreliable on network shares and WSL mounts.</p>
 */
public final class SavedDataTracker {

    private static final Logger logger = LoggerFactory.getLogger(SavedDataTracker.class);

    /** How the open image relates to its saved copy. */
    public enum State {
        /** Same objects on both sides, as far as the signals can tell. */
        IN_SYNC,
        /** The viewer has edits that are not saved. */
        LIVE_AHEAD,
        /** The saved copy changed while the image was open; the viewer shows older objects. */
        SAVED_AHEAD,
        /** Both: the saved copy changed AND the viewer has its own unsaved edits. */
        BOTH,
        /** No image, not in a project, or no saved copy yet. */
        UNTRACKED
    }

    /** One check's answer. {@code savedAtMillis} is the data file's modification time, 0 when unknown. */
    public record Status(State state, long savedAtMillis, String imageName) {
        public boolean savedAhead() { return state == State.SAVED_AHEAD || state == State.BOTH; }
        public boolean liveAhead() { return state == State.LIVE_AHEAD || state == State.BOTH; }
    }

    /** The pure decision, kept apart from QuPath so a table test can cover it. */
    record Decision(State state, long baseline, boolean mirrorDirty) {}

    private static SavedDataTracker instance;

    private final QuPathGUI qupath;
    private ImageData<BufferedImage> tracked;
    private long baseline;
    private volatile boolean mirrorDirty;
    private final PathObjectHierarchyListener hierarchyListener = this::hierarchyChanged;
    // Held so the weak listener on imageDataProperty is not collected.
    private final ChangeListener<ImageData<BufferedImage>> imageListener =
            (obs, oldData, newData) -> track(newData);

    private SavedDataTracker(QuPathGUI qupath) {
        this.qupath = qupath;
        qupath.imageDataProperty().addListener(new WeakChangeListener<>(imageListener));
        track(qupath.getImageData());
    }

    /** Installs the tracker once per QuPath; later calls return the same one. */
    public static synchronized SavedDataTracker install(QuPathGUI qupath) {
        if (instance == null) instance = new SavedDataTracker(qupath);
        return instance;
    }

    /** The installed tracker, or null before {@link #install}. */
    public static synchronized SavedDataTracker getInstance() {
        return instance;
    }

    private void hierarchyChanged(PathObjectHierarchyEvent event) {
        mirrorDirty = true;
    }

    private synchronized void track(ImageData<BufferedImage> data) {
        if (tracked != null && tracked.getHierarchy() != null) {
            tracked.getHierarchy().removeListener(hierarchyListener);
        }
        tracked = data;
        mirrorDirty = false;
        baseline = modifiedMillis(dataFile(data));
        if (data != null && data.getHierarchy() != null) {
            data.getHierarchy().addListener(hierarchyListener);
        }
    }

    /**
     * Checks the open image now. Cheap (one file stat), safe from any thread; call it on
     * demand rather than on a timer.
     */
    public synchronized Status check() {
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data != tracked) track(data);
        Path file = dataFile(data);
        if (data == null || file == null) return new Status(State.UNTRACKED, 0, null);
        long modified = modifiedMillis(file);
        if (modified <= 0) return new Status(State.UNTRACKED, 0, imageName(data));
        Decision d = decide(data.isChanged(), mirrorDirty, baseline, modified);
        baseline = d.baseline();
        mirrorDirty = d.mirrorDirty();
        return new Status(d.state(), modified, imageName(data));
    }

    /**
     * @param quPathChanged QuPath's own flag: anything changed since the last load or save.
     *        It is true right after an image opens (QuPath sets the image type and metadata
     *        then), so it only serves to recognize a save, never as "live ahead" by itself.
     * @param mirrorDirty this class saw a hierarchy change since the baseline was taken
     * @param baseline the data file's modification time when the image was last known in sync
     * @param modified the data file's modification time now
     */
    static Decision decide(boolean quPathChanged, boolean mirrorDirty, long baseline, long modified) {
        if (mirrorDirty && !quPathChanged) {
            // The live image was saved since we last looked: that save is the new baseline.
            return new Decision(State.IN_SYNC, modified, false);
        }
        boolean savedAhead = modified > baseline;
        State state = savedAhead
                ? (mirrorDirty ? State.BOTH : State.SAVED_AHEAD)
                : (mirrorDirty ? State.LIVE_AHEAD : State.IN_SYNC);
        return new Decision(state, baseline, mirrorDirty);
    }

    /** The user says the newer saved copy is fine (their own save): stop reporting it. */
    public synchronized void acceptSaved() {
        baseline = modifiedMillis(dataFile(tracked));
    }

    private Path dataFile(ImageData<BufferedImage> data) {
        if (data == null) return null;
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) return null;
        ProjectImageEntry<BufferedImage> entry;
        try {
            entry = project.getEntry(data);
        } catch (RuntimeException e) {
            return null;
        }
        if (entry == null) return null;
        Path path = entry.getEntryPath();
        return path == null ? null : path.resolve("data.qpdata");
    }

    private static long modifiedMillis(Path file) {
        if (file == null) return 0;
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException e) {
            logger.debug("Cannot stat {}: {}", file, e.getMessage());
            return 0;
        }
    }

    private static String imageName(ImageData<BufferedImage> data) {
        try {
            return data.getServer().getMetadata().getName();
        } catch (RuntimeException e) {
            return "the open image";
        }
    }

    /** For tests and the banner: the FX thread is not required by this class. */
    static boolean onFxThread() {
        return Platform.isFxApplicationThread();
    }

    @Override
    public String toString() {
        return "SavedDataTracker[" + Objects.toString(tracked) + ", baseline=" + baseline + "]";
    }
}
