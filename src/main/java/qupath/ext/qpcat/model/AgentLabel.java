package qupath.ext.qpcat.model;

/**
 * The label an MCP agent gives for itself. It is untrusted network text, so it
 * is cleaned once, here, before it is stored, logged or shown.
 */
public final class AgentLabel {

    /** Longest label kept, in characters, before "..." is appended. */
    public static final int MAX_LENGTH = 60;

    /** What a missing or empty label reads as. */
    public static final String UNNAMED = "an unnamed agent";

    private AgentLabel() {}

    /**
     * Clean a self-reported agent label: ISO control characters removed, trimmed,
     * cut at {@link #MAX_LENGTH} characters with "..." appended when cut.
     * Cleaning an already cleaned label returns it unchanged.
     *
     * @param raw the label as received; may be null
     * @return the cleaned label, or {@link #UNNAMED} when nothing is left
     */
    public static String clean(String raw) {
        if (raw == null) {
            return UNNAMED;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String label = sb.toString().trim();
        if (label.isEmpty()) {
            return UNNAMED;
        }
        if (label.length() <= MAX_LENGTH) {
            return label;
        }
        // Not trimmed again after the cut, so a cleaned label cleans to itself.
        return label.substring(0, MAX_LENGTH) + "...";
    }
}
