package qupath.ext.qpcat.ui;

import java.util.function.BinaryOperator;
import java.util.function.Predicate;

import qupath.ext.qpcat.service.CellClasses;

/**
 * Asks for a class name until {@link CellClasses#typedName} accepts it. A refused
 * name is explained above the question and the box is prefilled with the
 * suggested spelling, so "CD8:T" comes back as "CD8-T" for the user to confirm.
 */
final class ClassNamePrompt {

    private ClassNamePrompt() {}

    /**
     * @param header  the question
     * @param seed    the text to prefill
     * @param context what the name is, for the refusal message ("Cluster name")
     * @param dialog  shows one input dialog: (header, prefill) -&gt; text, or null on cancel
     * @param keep    text returned as typed without the check (a caller's own
     *                keyword); null for none
     * @return the accepted name, or null when the user canceled
     */
    static String ask(String header, String seed, String context,
                      BinaryOperator<String> dialog, Predicate<String> keep) {
        String value = seed;
        String problem = null;
        while (true) {
            String raw = dialog.apply(problem == null ? header : problem + "\n\n" + header, value);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            if (keep != null && keep.test(raw.trim())) {
                return raw.trim();
            }
            try {
                return CellClasses.typedName(raw, context);
            } catch (CellClasses.ClassNameException ex) {
                problem = ex.getMessage();
                value = ex.suggestion() != null ? ex.suggestion() : raw.trim();
            }
        }
    }
}
