package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A value of the wrong type is refused (E003) with the key and the value named,
 * never replaced by a default, truncated or retyped by YAML 1.1 rules (H05, H27).
 */
class BatchYamlCoercionTest {

    private static final String BASE = "version: '1.0'\nscope: { projects: [/tmp/p] }\n";

    private static BatchYamlParser.ParseOutcome parse(String yaml) {
        return BatchYamlParser.parseString(BASE + yaml);
    }

    private static Optional<ValidationIssue> issue(BatchYamlParser.ParseOutcome po, String code,
                                                   String path) {
        return po.getIssues().stream()
                .filter(i -> code.equals(i.getCode()) && path.equals(i.getFieldPath()))
                .findFirst();
    }

    private static boolean noErrors(BatchYamlParser.ParseOutcome po) {
        return po.getIssues().stream()
                .noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
    }

    // ---- numbers ----

    @Test
    void decimalCommaIsRefusedNotReplacedByTheDefault() {
        var po = parse("phenotyping:\n  rules:\n    - name: T\n      require_markers: [A]\n"
                + "      require_min_zscore: 0,5\n");
        var e = issue(po, "E003", "phenotyping.rules[0].require_min_zscore");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("0,5").contains("decimal point");
    }

    @Test
    void decimalCommaInAnOptionalNumberIsRefused() {
        var po = parse("clustering: { type: leiden, resolution: '0,5' }\n");
        assertThat(issue(po, "E003", "clustering.resolution")).isPresent();
        assertThat(po.getSchema().getClustering().getResolution()).isNull();
    }

    @Test
    void nonIntegralNumberForAWholeNumberKeyIsRefusedNotTruncated() {
        var po = parse("clustering: { type: kmeans, k: 2.7 }\n");
        var e = issue(po, "E003", "clustering.k");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("2.7");
        assertThat(po.getSchema().getClustering().getK()).isNull();
    }

    @Test
    void integralDecimalIsTheSameWholeNumber() {
        var po = parse("clustering: { type: kmeans, k: 3.0 }\nfigure_export:\n"
                + "  output_dir: /tmp/o\n  dpi: 300.0\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getClustering().getK()).isEqualTo(3);
        assertThat(po.getSchema().getFigureExport().getDpi()).isEqualTo(300);
    }

    @Test
    void quotedPlainNumberIsAccepted() {
        var po = parse("clustering: { type: kmeans, k: '15' }\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getClustering().getK()).isEqualTo(15);
    }

    @Test
    void textForANumberIsRefused() {
        var po = parse("workers: abc\n");
        var e = issue(po, "E003", "workers");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("abc");
    }

    @Test
    void leadingZeroIsNotReadAsOctal() {
        var po = parse("clustering: { type: leiden, pca_n_components: 010 }\n");
        var e = issue(po, "E003", "clustering.pca_n_components");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("010").contains("leading zeros");
        assertThat(po.getSchema().getClustering().getPcaNComponents())
                .as("never 8").isNull();
    }

    @Test
    void colonNumberIsNotReadAsSexagesimal() {
        var po = parse("clustering: { type: leiden, random_seed: 1:30 }\n");
        var e = issue(po, "E003", "clustering.random_seed");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("1:30");
        assertThat(po.getSchema().getClustering().getRandomSeed()).as("never 90").isNull();
    }

    @Test
    void underscoreAndHexAreNotNumbers() {
        var po = parse("clustering: { type: leiden, random_seed: 1_000, embedding_seed: 0x10 }\n");
        assertThat(issue(po, "E003", "clustering.random_seed")).isPresent();
        assertThat(issue(po, "E003", "clustering.embedding_seed")).isPresent();
    }

    @Test
    void exponentNumbersStillWork() {
        var po = parse("clustering: { type: leiden, embedding: tsne, tsne_learning_rate: 1e3 }\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getClustering().getTsneLearningRate()).isEqualTo(1000.0);
    }

    @Test
    void fractionalPermutationsAreRefused() {
        var po = parse("spatial_stats:\n  graph: { type: knn, k: 2.5 }\n"
                + "  statistics: [moran_i]\n  permutations: 99.9\n");
        assertThat(issue(po, "E003", "spatial_stats.permutations")).isPresent();
        assertThat(issue(po, "E003", "spatial_stats.graph.k")).isPresent();
    }

    @Test
    void permutationsAutoStaysText() {
        var po = parse("spatial_stats:\n  graph: { type: knn }\n  statistics: [moran_i]\n"
                + "  permutations: auto\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getSpatialStats().getPermutations()).isEqualTo("auto");
    }

    // ---- booleans ----

    @Test
    void yesAndOnStillMeanTrueForSwitches() {
        var po = parse("clustering: { type: leiden, joint: yes, spatial_smoothing: on }\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getClustering().isJoint()).isTrue();
        assertThat(po.getSchema().getClustering().getSpatialSmoothing()).isTrue();
    }

    @Test
    void anUnreadableSwitchIsRefusedNotDefaulted() {
        var po = parse("phenotyping:\n  enabled: maybe\n  rules: []\n");
        var e = issue(po, "E003", "phenotyping.enabled");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("maybe");
    }

    // ---- names ----

    @Test
    void unquotedColonNameInAListIsRefusedWithAQuoteHint() {
        var po = parse("clustering:\n  type: leiden\n  measurements:\n"
                + "    - Nucleus: Area\n    - 'Cell: CD3 mean'\n");
        var e = issue(po, "E003", "clustering.measurements[0]");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("Nucleus: Area").contains("quotes");
        assertThat(po.getSchema().getClustering().getMeasurements())
                .containsExactly("Cell: CD3 mean");
    }

    @Test
    void trueNumberAndEmptyEntriesInANameListAreRefused() {
        var po = parse("clustering:\n  type: leiden\n  measurements: [true, 1e3, ~, 'CD3']\n");
        assertThat(issue(po, "E003", "clustering.measurements[0]")).isPresent();
        assertThat(issue(po, "E003", "clustering.measurements[1]")).isPresent();
        assertThat(issue(po, "E003", "clustering.measurements[2]")).isPresent();
        assertThat(po.getSchema().getClustering().getMeasurements()).containsExactly("CD3");
    }

    @Test
    void yesAndLeadingZeroNamesStayExactlyAsTyped() {
        var po = parse("clustering:\n  type: leiden\n  measurements: [yes, 010, '1:30']\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getClustering().getMeasurements())
                .containsExactly("yes", "010", "1:30");
    }

    @Test
    void ruleNameReadAsBooleanIsRefused() {
        var po = parse("phenotyping:\n  rules:\n    - name: true\n      require_markers: [A]\n");
        assertThat(issue(po, "E003", "phenotyping.rules[0].name")).isPresent();
    }

    @Test
    void dateLikeRunNameStaysText() {
        var po = parse("audit: { run_name: 2026-05-13 }\n");
        assertThat(noErrors(po)).isTrue();
        assertThat(po.getSchema().getAudit().getRunName()).isEqualTo("2026-05-13");
    }

    @Test
    void numericScopeImageNameIsRefused() {
        var listed = BatchYamlParser.parseString(
                "version: '1.0'\nscope:\n  projects: [/tmp/p]\n  images: [slide_1, 2]\n");
        assertThat(issue(listed, "E003", "scope.images[1]")).isPresent();
    }

    // ---- repeats ----

    @Test
    void duplicateKeyIsRefusedNotLastWins() {
        var po = BatchYamlParser.parseString(BASE + "clustering:\n  type: kmeans\n  k: 15\n  k: 20\n");
        assertThat(po.getIssues()).anyMatch(i -> i.getCode().equals("E022")
                && i.getMessage().contains("'k'"));
    }

    @Test
    void duplicateMeasurementIsRefused() {
        var po = parse("clustering:\n  type: leiden\n"
                + "  measurements: ['Cell: CD3 mean', 'Cell: CD8 mean', 'Cell: CD3 mean']\n");
        var e = issue(po, "E023", "clustering.measurements");
        assertThat(e).isPresent();
        assertThat(e.get().getMessage()).contains("Cell: CD3 mean");
    }

    @Test
    void duplicateRuleMarkerAndScopeImageAreRefused() {
        var po = BatchYamlParser.parseString("version: '1.0'\nscope:\n  projects: [/tmp/p]\n"
                + "  images: [a, b, a]\nphenotyping:\n  rules:\n    - name: T\n"
                + "      require_markers: [X, X]\n");
        assertThat(issue(po, "E023", "scope.images")).isPresent();
        assertThat(issue(po, "E023", "phenotyping.rules[0].require_markers")).isPresent();
    }

    @Test
    void messagesAreAscii() {
        var po = parse("clustering:\n  type: leiden\n  measurements:\n    - Nucleus: Area\n"
                + "  pca_n_components: 010\n");
        List<ValidationIssue> issues = po.getIssues();
        assertThat(issues).isNotEmpty();
        for (ValidationIssue i : issues) {
            assertThat(i.format().chars().allMatch(c -> c < 128)).isTrue();
        }
    }
}
