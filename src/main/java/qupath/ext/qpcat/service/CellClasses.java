package qupath.ext.qpcat.service;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

/**
 * One definition of "this cell has no classification", and the name to show when
 * it does not.
 *
 * <p><b>Why this exists.</b> A detection can carry no class in two ways: a
 * {@code null} {@code PathClass}, or {@link PathClass#getNullClass()}, which is a
 * real singleton object and is not {@code null}. Code that tests only one half
 * reads the same cell as classified in one dialog and unclassified in another.
 * {@code PathClass.getNullClass().toString()} returns {@code "Unclassified"}
 * (QuPath's own {@code defaultName}), so the singleton does not announce itself
 * as absent -- it looks exactly like a class called Unclassified.
 */
public final class CellClasses {

    /**
     * What to call a cell with no classification. This is the string QuPath's own
     * null class reports, so a display built from it agrees with QuPath.
     *
     * <p>Not a safe sentinel: a project can contain a real class of this name, and
     * one bug this class exists to stop used to create exactly that. Code that
     * needs to mean "clear the classification" must use a value no class can
     * take, not this constant.
     */
    public static final String UNCLASSIFIED_DISPLAY = "Unclassified";

    private CellClasses() {}

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
     * For counting and display, where every cell needs a bucket.
     *
     * @param object detection to inspect; not modified
     * @return a non-null name
     */
    public static String displayNameOf(PathObject object) {
        String name = nameOf(object);
        return name != null ? name : UNCLASSIFIED_DISPLAY;
    }
}
