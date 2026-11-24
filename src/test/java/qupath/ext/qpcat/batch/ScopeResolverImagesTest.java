package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code scope.images} selection (H04) and duplicate image names in scope (H23). */
class ScopeResolverImagesTest {

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry(String name, String id) {
        ProjectImageEntry<BufferedImage> e = mock(ProjectImageEntry.class);
        when(e.getImageName()).thenReturn(name);
        when(e.getID()).thenReturn(id);
        return e;
    }

    private static final List<ProjectImageEntry<BufferedImage>> PROJECT = List.of(
            entry("slide_01.tif", "1"), entry("slide_02.tif", "2"), entry("slide_03.tif", "3"));

    private static BatchYamlSchema.ScopeBlock scope(Object images) {
        BatchYamlSchema.ScopeBlock s = new BatchYamlSchema.ScopeBlock();
        s.setImages(images);
        return s;
    }

    private static List<String> names(List<ProjectImageEntry<BufferedImage>> entries) {
        List<String> out = new ArrayList<>();
        for (var e : entries) out.add(e.getImageName());
        return out;
    }

    @Test
    void bareStringIsOneImageNotEveryImage() {
        ValidationResult issues = new ValidationResult();
        var got = ScopeResolver.filterImages(PROJECT, scope("slide_02.tif"), issues);
        assertThat(names(got)).containsExactly("slide_02.tif");
        assertThat(issues.hasErrors()).isFalse();
    }

    @Test
    void bareStringThatIsNotAnImageIsE008() {
        for (String typo : new String[] {"none", "all ", "slide_9.tif"}) {
            ValidationResult issues = new ValidationResult();
            var got = ScopeResolver.filterImages(PROJECT, scope(typo), issues);
            assertThat(got).as(typo).isEmpty();
            assertThat(issues.getIssues()).as(typo)
                    .anyMatch(i -> i.getCode().equals("E008")
                            && i.getFieldPath().equals("scope.images"));
        }
    }

    @Test
    void allInAnyCaseIsEveryImage() {
        ValidationResult issues = new ValidationResult();
        assertThat(ScopeResolver.filterImages(PROJECT, scope("ALL"), issues)).hasSize(3);
        assertThat(ScopeResolver.filterImages(PROJECT, scope(null), issues)).hasSize(3);
    }

    @Test
    void listedNameMatchingTwoEntriesSelectsBothSoTheRepeatIsReported() {
        List<ProjectImageEntry<BufferedImage>> project = List.of(
                entry("core.tif", "a1"), entry("other.tif", "b2"), entry("core.tif", "c3"));
        ValidationResult issues = new ValidationResult();
        var got = ScopeResolver.filterImages(project, scope(List.of("core.tif")), issues);
        assertThat(got).hasSize(2);
        ScopeResolver.refuseDuplicateNames(got, "/p/project.qpproj", issues);
        assertThat(issues.getIssues()).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("E024");
            assertThat(i.getMessage()).contains("core.tif").contains("a1").contains("c3");
        });
    }

    @Test
    void duplicateNamesUnderAllAreRefused() {
        List<ProjectImageEntry<BufferedImage>> project = List.of(
                entry("core.tif", "a1"), entry("core.tif", "c3"), entry("x.tif", "x"));
        ValidationResult issues = new ValidationResult();
        ScopeResolver.refuseDuplicateNames(
                ScopeResolver.filterImages(project, scope("all"), issues), "p", issues);
        assertThat(issues.hasErrors()).isTrue();
    }

    @Test
    void duplicatesOutsideTheScopeDoNotMatter() {
        List<ProjectImageEntry<BufferedImage>> project = List.of(
                entry("core.tif", "a1"), entry("core.tif", "c3"), entry("x.tif", "x"));
        ValidationResult issues = new ValidationResult();
        ScopeResolver.refuseDuplicateNames(
                ScopeResolver.filterImages(project, scope(List.of("x.tif")), issues), "p", issues);
        assertThat(issues.getIssues()).isEmpty();
    }
}
