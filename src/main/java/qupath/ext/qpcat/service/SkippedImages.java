package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/**
 * The images a project run left out (could not read, or failed on), for the completion
 * message and the saved notes. A run over 20 images that silently used 19 reads like a run
 * over 20.
 */
public final class SkippedImages {

    private static final int NAMES_SHOWN = 10;

    private final List<String> entries = new ArrayList<>();

    /**
     * Records an image that could not be read, and logs it.
     *
     * @param logger    the caller's logger
     * @param imageName the image's name
     * @param error     why it could not be read
     */
    public void unreadable(Logger logger, String imageName, Exception error) {
        add(logger, imageName, "could not be read", error);
    }

    /**
     * Records an image left out of the run, and logs it.
     *
     * @param logger    the caller's logger
     * @param imageName the image's name
     * @param what      what went wrong, e.g. "could not be read", "inference failed"
     * @param error     the cause, or null
     */
    public void add(Logger logger, String imageName, String what, Exception error) {
        String reason = error == null ? null
                : error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName() : error.getMessage().strip();
        logger.warn("{} {}; left out of the run{}", imageName, what, reason == null ? "" : ": " + reason);
        entries.add(imageName + " (" + what + (reason == null ? "" : ": " + oneLine(reason)) + ")");
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    public int size() { return entries.size(); }

    /**
     * One sentence naming the images left out and why, or null when none was.
     *
     * @return the note
     */
    public String note() {
        if (entries.isEmpty()) return null;
        List<String> shown = entries.subList(0, Math.min(NAMES_SHOWN, entries.size()));
        String more = entries.size() > NAMES_SHOWN ? "; and " + (entries.size() - NAMES_SHOWN) + " more" : "";
        return entries.size() + " image(s) were left out of this run: "
                + String.join("; ", shown) + more + ".";
    }

    /**
     * {@code notes} with {@link #note()} appended when an image was left out.
     *
     * @param notes the notes so far; not modified
     * @return a new list, or {@code notes} itself when nothing was skipped
     */
    public List<String> appendTo(List<String> notes) {
        String note = note();
        if (note == null) return notes;
        List<String> all = new ArrayList<>(notes == null ? List.of() : notes);
        all.add(note);
        return all;
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
