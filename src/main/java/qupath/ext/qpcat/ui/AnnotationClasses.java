package qupath.ext.qpcat.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The classes a multi-image dialog can offer for "pick an annotation class".
 * <p>
 * Reading only the open image is wrong for a project-scoped run -- the class the
 * user wants may live in an image that is not open, or no image may be open at
 * all -- and reading every image in the project to find out would mean opening
 * each one, which is far too slow for a control that rebuilds on every click.
 */
final class AnnotationClasses {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationClasses.class);

    private AnnotationClasses() {}

    /**
     * Classes actually on annotations in the open image, if any.
     *
     * @param qupath the GUI whose current image is read; may be null
     * @return the class names, in hierarchy order
     */
    @SuppressWarnings("unchecked")
    static List<String> onOpenImageAnnotations(QuPathGUI qupath) {
        Set<String> names = new LinkedHashSet<>();
        ImageData<BufferedImage> imageData =
                qupath == null ? null : (ImageData<BufferedImage>) qupath.getImageData();
        if (imageData != null) {
            for (PathObject annotation : imageData.getHierarchy().getAnnotationObjects()) {
                PathClass pc = annotation.getPathClass();
                if (pc != null && pc != PathClass.getNullClass()) {
                    names.add(pc.toString());
                }
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * The project's class list, minus anything already on the open image's
     * annotations. These are the classes a run may meet in an image other than
     * the open one.
     *
     * @param qupath the GUI whose available classes are read; may be null
     * @return the remaining class names
     */
    static List<String> elsewhereInProject(QuPathGUI qupath) {
        Set<String> names = new LinkedHashSet<>();
        if (qupath == null) {
            return new ArrayList<>(names);
        }
        Set<String> onImage = new LinkedHashSet<>(onOpenImageAnnotations(qupath));
        try {
            for (PathClass pc : qupath.getAvailablePathClasses()) {
                if (pc != null && pc != PathClass.getNullClass()
                        && pc.toString() != null && !pc.toString().isBlank()
                        && !onImage.contains(pc.toString())) {
                    names.add(pc.toString());
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read the project class list: {}", e.getMessage());
        }
        return new ArrayList<>(names);
    }

    /** The open image's annotation classes first, then the rest of the project's. */
    static List<String> inScope(QuPathGUI qupath) {
        List<String> all = new ArrayList<>(onOpenImageAnnotations(qupath));
        all.addAll(elsewhereInProject(qupath));
        return all;
    }
}
