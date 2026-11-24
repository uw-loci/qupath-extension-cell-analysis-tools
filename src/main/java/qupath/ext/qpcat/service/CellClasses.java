package qupath.ext.qpcat.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import qupath.ext.qpcat.model.PhenotypeNames;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

/**
 * One definition of "this cell has no classification", the name to show when
 * it does not, and the one gate every class name built from text goes through.
 *
 * <p><b>No class.</b> A detection can carry no class in two ways: a
 * {@code null} {@code PathClass}, or {@link PathClass#getNullClass()}, which is a
 * real singleton object and is not {@code null}. Code that tests only one half
 * reads the same cell as classified in one dialog and unclassified in another.
 * {@code PathClass.getNullClass().toString()} returns {@code "Unclassified"}
 * (QuPath's own {@code defaultName}), so the singleton does not announce itself
 * as absent -- it looks exactly like a class called Unclassified.
 *
 * <p><b>Class names from text.</b> QuPath reads {@code :} as the separator of a
 * DERIVED class ("CD8:T" becomes the two-part class "CD8: T"), throws on a blank
 * part and on a newline, and drops a trailing {@code :}. A name handed to
 * {@code PathClass.fromString} unchecked can therefore become a different class
 * than the one QP-CAT saved or showed. {@link #forTypedName} (a name a person
 * typed) and {@link #forName} (a name read from data or produced by a classifier)
 * are the only ways QP-CAT turns text into a class; {@code ClassNameGateLintTest}
 * holds every other source file to that.
 */
public final class CellClasses {

    /**
     * What to call a cell with no classification. This is the string QuPath's own
     * null class reports, so a display built from it agrees with QuPath.
     *
     * <p>Not a safe sentinel: a project can contain a real class of this name.
     * Code that needs to mean "no class" keys on null (or the null class), and
     * shows this word through {@link #noClassLabel} so a real class of the same
     * name keeps its own row.
     */
    public static final String UNCLASSIFIED_DISPLAY = "Unclassified";

    /** What phenotyping and the AnnData export call a cell no rule or class names. */
    public static final String UNKNOWN_DISPLAY = "Unknown";

    /**
     * Words QP-CAT shows for "no class" or "no phenotype". Refused as typed class
     * names (in any case), because a class spelled like one of them cannot be told
     * apart from the absence it stands for.
     */
    public static final List<String> RESERVED_NAMES =
            List.of(UNCLASSIFIED_DISPLAY, UNKNOWN_DISPLAY, "(Unclassified)");

    private CellClasses() {}

    /** A class name that was refused, with a spelling to offer instead when there is one. */
    public static final class ClassNameException extends IllegalArgumentException {

        private final String suggestion;

        ClassNameException(String message, String suggestion) {
            super(message);
            this.suggestion = suggestion;
        }

        /** A usable spelling of the refused name, or null when there is none. */
        public String suggestion() {
            return suggestion;
        }
    }

    /** True when the class is absent, counting the null-class singleton. */
    public static boolean isUnclassified(PathClass pathClass) {
        return pathClass == null || pathClass == PathClass.getNullClass();
    }

    /** True when the object carries no classification. */
    public static boolean isUnclassified(PathObject object) {
        return object == null || isUnclassified(object.getPathClass());
    }

    /**
     * The object's class name, or null when it has none.
     *
     * @param object detection to inspect; not modified
     * @return the class name, or null if unclassified
     */
    public static String nameOf(PathObject object) {
        return isUnclassified(object) ? null : object.getPathClass().toString();
    }

    /**
     * The object's class name, or {@link #UNCLASSIFIED_DISPLAY} when it has none.
     * For a log line or a single-image display only: it gives a real class called
     * Unclassified and a cell with no class the same name. Anything that counts or
     * groups keys on {@link #nameOf} and labels with {@link #noClassLabel}.
     *
     * @param object detection to inspect; not modified
     * @return a non-null name
     */
    public static String displayNameOf(PathObject object) {
        String name = nameOf(object);
        return name != null ? name : UNCLASSIFIED_DISPLAY;
    }

    /**
     * The class for a name a person typed: a rename, a gate's class, a phenotype
     * cell type, an uncertain-cell answer.
     *
     * @param raw     the text as typed
     * @param context what the name is, for the message ("Cluster name", "Cell type")
     * @return the class, whose {@code toString()} equals {@link #typedName}
     * @throws ClassNameException when the name is blank, holds a control character
     *                            or {@code :}, or is one of {@link #RESERVED_NAMES}
     */
    public static PathClass forTypedName(String raw, String context) {
        return PathClass.fromString(typedName(raw, context));
    }

    /**
     * A typed class name, checked and trimmed. See {@link #forTypedName}.
     *
     * @param raw     the text as typed
     * @param context what the name is, for the message
     * @return the name to store and show
     * @throws ClassNameException when the name is refused
     */
    public static String typedName(String raw, String context) {
        String name = basicName(raw, context);
        if (name.contains(PathClass.DELIMITER)) {
            String suggestion = PhenotypeNames.plainSeparators(name);
            if (suggestion.isEmpty() || isReservedName(suggestion)) {
                suggestion = null;
            }
            throw new ClassNameException(context + " '" + name + "' contains ':', which QuPath "
                    + "reads as a derived class (a class inside a class), so the cells would "
                    + "carry '" + derivedSpelling(name) + "' instead. Use a name without ':'"
                    + (suggestion != null ? ", for example '" + suggestion + "'." : "."),
                    suggestion);
        }
        if (isReservedName(name)) {
            throw new ClassNameException(context + " '" + name + "' is reserved: QP-CAT shows '"
                    + UNCLASSIFIED_DISPLAY + "' for cells with no class and '" + UNKNOWN_DISPLAY
                    + "' for cells no phenotype rule matched, so a class with that name could "
                    + "not be told apart from them. Choose another name.", null);
        }
        return canonical(name, context);
    }

    /**
     * The class for a name that arrived from data rather than from a person: an
     * imported AnnData category, a saved result's cluster name, a classifier's
     * output. A {@code :} keeps QuPath's derived-class meaning, as it had in the
     * tool the name came from.
     *
     * @param raw     the name as read
     * @param context what the name is, for the message
     * @return the class; its {@code toString()} is {@link #canonicalName}
     * @throws ClassNameException when the name is blank, holds a control character,
     *                            or is not a valid QuPath class (such as "A::B")
     */
    public static PathClass forName(String raw, String context) {
        return PathClass.fromString(canonicalName(raw, context));
    }

    /**
     * The spelling QuPath gives a name read from data: "CD8:T" is "CD8: T", "CD8:" is
     * "CD8". Store this, not the raw text, so a saved name and the cells' class agree.
     *
     * @param raw     the name as read
     * @param context what the name is, for the message
     * @return the class's {@code toString()}
     * @throws ClassNameException as {@link #forName}
     */
    public static String canonicalName(String raw, String context) {
        return canonical(basicName(raw, context), context);
    }

    /**
     * The color of the class a display name refers to, for charts and legends.
     *
     * @param name a class name; may be null
     * @return the class's color, or null when it has none or the name is not a class
     */
    public static Integer colorOf(String name) {
        try {
            return name == null ? null : forName(name, "Class name").getColor();
        } catch (ClassNameException e) {
            return null;
        }
    }

    /**
     * True when {@code name}, trimmed, is one of {@link #RESERVED_NAMES} in any case.
     *
     * @param name a class name; may be null
     * @return whether a person may not type it as a class name
     */
    public static boolean isReservedName(String name) {
        if (name == null) {
            return false;
        }
        String t = name.trim();
        return RESERVED_NAMES.stream().anyMatch(r -> r.equalsIgnoreCase(t));
    }

    /**
     * The label for cells with no class (or no phenotype) shown next to real class
     * names: {@code word} itself, unless a real class already has that name, in
     * which case the no-class cells are shown as "{@code word} (no class)" so the
     * two never share a row, a column or a count.
     *
     * @param realNames the real class names in the same output
     * @param word      the usual word, {@link #UNCLASSIFIED_DISPLAY} or {@link #UNKNOWN_DISPLAY}
     * @return a label no name in {@code realNames} equals
     */
    public static String noClassLabel(Collection<String> realNames, String word) {
        String label = word;
        int n = 1;
        while (realNames.contains(label)) {
            label = word + (n == 1 ? " (no class)" : " (no class " + n + ")");
            n++;
        }
        return label;
    }

    /**
     * Display labels for class names where null means "no class": each name as it
     * is, and null as {@link #noClassLabel} against the others.
     *
     * @param names class names, null for no class; not modified
     * @param word  the word for no class, {@link #UNCLASSIFIED_DISPLAY} or {@link #UNKNOWN_DISPLAY}
     * @return labels, index-aligned with {@code names}, all distinct when the names are
     */
    public static List<String> labelsFor(List<String> names, String word) {
        List<String> real = names.stream().filter(n -> n != null).toList();
        String noClass = noClassLabel(real, word);
        return names.stream().map(n -> n == null ? noClass : n).toList();
    }

    private static String basicName(String raw, String context) {
        if (raw == null || raw.isBlank()) {
            throw new ClassNameException(context + " is empty.", null);
        }
        String name = raw.trim();
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            String suggestion = PhenotypeNames.plainSeparators(name);
            throw new ClassNameException(context + " '" + printable(name) + "' contains a line "
                    + "break, tab or other control character, which breaks the class list, "
                    + "legends and every CSV export. Use a name on one line.",
                    suggestion.isEmpty() ? null : suggestion);
        }
        return name;
    }

    private static String canonical(String name, String context) {
        PathClass pc;
        try {
            pc = PathClass.fromString(name);
        } catch (IllegalArgumentException e) {
            throw new ClassNameException(context + " '" + name + "' is not a valid QuPath class "
                    + "name (" + e.getMessage() + "). An empty part between two ':' is the "
                    + "usual cause.", null);
        }
        if (pc == null || pc == PathClass.getNullClass() || !pc.isValid()) {
            throw new ClassNameException(context + " '" + name + "' is not a valid QuPath "
                    + "class name.", null);
        }
        return pc.toString();
    }

    /** The derived spelling of a name, or the name itself when QuPath cannot read it. */
    private static String derivedSpelling(String name) {
        try {
            return PathClass.fromString(name).toString();
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    /** The name with each control character written as an escape, for a message. */
    private static String printable(String name) {
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", c));
            } else {
                sb.appendCodePoint(c);
            }
        });
        return sb.toString();
    }
}
