package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a single-image run was narrowed to: the selected annotations, or the
 * selected detections. A run over every detection has no selection to record.
 *
 * @param annotations        the selected annotations, empty when none was selected
 * @param selectedDetections how many detections were selected, when no annotation was
 */
public record InputSelection(List<Annotation> annotations, int selectedDetections) {

    /**
     * One selected annotation.
     *
     * @param name           its name, or null when unnamed
     * @param classification its class, or null when unclassified
     * @param id             QuPath's object ID
     * @param detections     detections inside it, before the cell and class filters
     */
    public record Annotation(String name, String classification, String id, int detections) {}

    /** Selected annotations narrowed the run. */
    public static InputSelection ofAnnotations(List<Annotation> annotations) {
        return new InputSelection(List.copyOf(annotations), 0);
    }

    /** Selected detections narrowed the run. */
    public static InputSelection ofDetections(int count) {
        return new InputSelection(List.of(), count);
    }

    /**
     * One line naming the selection: {@code 3 annotations (Tumor x2, Stroma x1)}
     * or {@code 100 selected detections}.
     *
     * @return the summary
     */
    public String summary() {
        if (annotations.isEmpty()) {
            return selectedDetections + " selected detection" + (selectedDetections == 1 ? "" : "s");
        }
        Map<String, Integer> byClass = new LinkedHashMap<>();
        for (Annotation a : annotations) {
            byClass.merge(a.classification() == null ? "Unclassified" : a.classification(), 1,
                    Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        byClass.forEach((cls, n) -> parts.add(cls + " x" + n));
        return annotations.size() + " annotation" + (annotations.size() == 1 ? "" : "s")
                + " (" + String.join(", ", parts) + ")";
    }

    /**
     * One line per annotation: class, name, ID and the detections inside it.
     *
     * @return the lines, empty for a detection selection
     */
    public List<String> detailLines() {
        List<String> lines = new ArrayList<>();
        for (Annotation a : annotations) {
            lines.add((a.classification() == null ? "Unclassified" : a.classification())
                    + "  '" + (a.name() == null ? "(unnamed)" : a.name()) + "'"
                    + "  id=" + a.id()
                    + "  " + a.detections() + " detection" + (a.detections() == 1 ? "" : "s")
                    + " inside");
        }
        return lines;
    }
}
