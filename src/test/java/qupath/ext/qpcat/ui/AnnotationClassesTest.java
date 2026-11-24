package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The independent-areas class picker keeps classes the open image and the project list do
 * not hold: a class typed in, or saved in a configuration, that only other images carry
 * (H48). Such a saved class used to be dropped when the configuration was loaded.
 */
class AnnotationClassesTest {

    @Test
    void extraClassesFollowTheInScopeOnesWithoutRepeats() {
        assertThat(AnnotationClasses.withExtra(List.of("Tissue", "Tumor"),
                new LinkedHashSet<>(List.of("Tumor", "Core region"))))
                .containsExactly("Tissue", "Tumor", "Core region");
        assertThat(AnnotationClasses.withExtra(List.of(), List.of("Only elsewhere")))
                .containsExactly("Only elsewhere");
    }

    @Test
    void noGuiNoClasses() {
        assertThat(AnnotationClasses.inScope(null)).isEmpty();
    }
}
