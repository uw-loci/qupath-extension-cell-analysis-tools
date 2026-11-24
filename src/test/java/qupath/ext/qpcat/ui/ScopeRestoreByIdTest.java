package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;
import qupath.lib.projects.ProjectImageEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H23: a stored "Specific images" scope comes back by entry id; two entries may share a name. */
class ScopeRestoreByIdTest {

    private static ProjectImageEntry<?> entry(String id, String name) {
        ProjectImageEntry<?> e = mock(ProjectImageEntry.class);
        when(e.getID()).thenReturn(id);
        when(e.getImageName()).thenReturn(name);
        return e;
    }

    @Test
    void aStoredScopeIsRestoredByIdAndByNameOnlyWithoutIds() {
        ProjectImageEntry<?> a = entry("7", "slide.tif");
        ProjectImageEntry<?> b = entry("9", "slide.tif");
        ProjectImageEntry<?> c = entry("3", "other.tif");
        List<ProjectImageEntry<?>> all = List.of(a, b, c);
        assertThat(ScopeSection.storedSubset(all, true, List.of("9"))).containsExactly(b);
        // A setting saved before ids knows only the name: every entry of it, as before.
        assertThat(ScopeSection.storedSubset(all, false, List.of("slide.tif")))
                .containsExactly(a, b);
        assertThat(ScopeSection.storedSubset(all, true, List.of("gone"))).isEmpty();
    }
}
