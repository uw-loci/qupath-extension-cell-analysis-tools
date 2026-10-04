package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.GateSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saving a gate, reading it back, and refusing to replay it where it would lie.
 *
 * <p>The load path is the one with a decision in it. A gate is a polygon in the
 * plot's own units, so it will happily draw itself onto any plot; whether the
 * cells underneath are the ones it was drawn around is a question about the
 * axes, and the answer differs between a biaxial plot (the axes are
 * measurements, so yes) and an embedding (the layout belongs to one run, so
 * usually no).
 */
class GateStoreTest {

    private static final GateSet.Axes UMAP = new GateSet.Axes(
            GateSet.MODE_EMBEDDING, "UMAP", "UMAP1", "UMAP2", "4 image(s)");
    private static final GateSet.Axes BIAXIAL = new GateSet.Axes(
            GateSet.MODE_BIAXIAL, "biaxial", "CD3: Mean", "CD8: Mean", "4 image(s)");

    private static final double[][] DATA = {
            {1, 1}, {2, 2}, {3, 3}, {8, 8}, {9, 1},
    };

    private static GateSet.Gate squareGate(String label) {
        return new GateSet.Gate(label,
                new double[][] {{0, 0}, {5, 0}, {5, 5}, {0, 5}}, 3);
    }

    private static GateSet setOn(GateSet.Axes axes, double[][] data) {
        return new GateSet(axes, data.length, GateSet.fingerprintOf(data),
                "0.21.0", "2026-10-04T12:00:00", List.of(squareGate("Population A")));
    }

    // ---- round trip ----

    @Test
    void aSavedGateComesBackWithItsGeometryAndItsContext(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a" + GateStore.EXTENSION);
        GateStore.write(file, setOn(UMAP, DATA));

        GateSet back = GateStore.read(file);
        assertThat(back.getFormatVersion()).isEqualTo(GateSet.FORMAT_VERSION);
        assertThat(back.getAxisMode()).isEqualTo(GateSet.MODE_EMBEDDING);
        assertThat(back.getAxisName()).isEqualTo("UMAP");
        assertThat(back.getColumnX()).isEqualTo("UMAP1");
        assertThat(back.getColumnY()).isEqualTo("UMAP2");
        assertThat(back.getScope()).isEqualTo("4 image(s)");
        assertThat(back.getNCells()).isEqualTo(5);
        assertThat(back.getQpcatVersion()).isEqualTo("0.21.0");
        assertThat(back.getDataFingerprint()).isEqualTo(GateSet.fingerprintOf(DATA));
        assertThat(back.getGates()).hasSize(1);
        assertThat(back.getGates().get(0).getLabel()).isEqualTo("Population A");
        assertThat(back.getGates().get(0).getVertices())
                .isDeepEqualTo(new double[][] {{0, 0}, {5, 0}, {5, 5}, {0, 5}});
        assertThat(back.getGates().get(0).getCellsWhenDrawn()).isEqualTo(3);
    }

    @Test
    void theVerticesSurviveAtFullPrecision(@TempDir Path dir) throws IOException {
        // Rounding a vertex moves the gate's edge, which moves cells across it.
        double[][] odd = {{-1.2345678901234e-3, 9.87654321e5},
                {0.1, 0.2}, {3.0000000001, -4.9999999999}};
        Path file = dir.resolve("b" + GateStore.EXTENSION);
        GateStore.write(file, new GateSet(BIAXIAL, 3, "x", "0.21.0", "now",
                List.of(new GateSet.Gate("g", odd, 1))));
        double[][] back = GateStore.read(file).getGates().get(0).getVertices();
        for (int i = 0; i < odd.length; i++) {
            assertThat(back[i][0]).isEqualTo(odd[i][0]);
            assertThat(back[i][1]).isEqualTo(odd[i][1]);
        }
    }

    @Test
    void theFileIsReadableJson(@TempDir Path dir) throws IOException {
        // It is a record someone may have to read without QuPath in front of them.
        Path file = dir.resolve("c" + GateStore.EXTENSION);
        GateStore.write(file, setOn(BIAXIAL, DATA));
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(json).contains("\"axisMode\": \"biaxial\"")
                .contains("\"columnX\": \"CD3: Mean\"")
                .contains("\"label\": \"Population A\"")
                .contains("\n");
    }

    // ---- refusing to read what it cannot read ----

    @Test
    void aFileFromANewerQpcatIsRefusedRatherThanPartlyUnderstood(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("d" + GateStore.EXTENSION);
        GateStore.write(file, setOn(UMAP, DATA));
        String bumped = Files.readString(file)
                .replace("\"formatVersion\": 1", "\"formatVersion\": 99");
        Files.writeString(file, bumped);
        assertThatThrownBy(() -> GateStore.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("newer QP-CAT");
    }

    @Test
    void aFileWithNoUsableGateSaysSo(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("e" + GateStore.EXTENSION);
        Files.writeString(empty, "{\"formatVersion\":1,\"gates\":[]}");
        assertThatThrownBy(() -> GateStore.read(empty))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No gates");

        Path degenerate = dir.resolve("f" + GateStore.EXTENSION);
        GateStore.write(degenerate, new GateSet(UMAP, 5, "x", "0.21.0", "now",
                List.of(new GateSet.Gate("line", new double[][] {{0, 0}, {1, 1}}, 0))));
        assertThatThrownBy(() -> GateStore.read(degenerate))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("three vertices");
    }

    @Test
    void somethingThatIsNotAGateFileIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("g" + GateStore.EXTENSION);
        Files.writeString(file, "this is not json at all");
        assertThatThrownBy(() -> GateStore.read(file)).isInstanceOf(IOException.class);
    }

    // ---- the decision: may this gate be replayed here? ----

    @Test
    void sameAxesAndSameCoordinatesIsAnExactMatch() {
        GateSet saved = setOn(UMAP, DATA);
        GateStore.Compatibility c = GateStore.check(
                saved, UMAP, GateSet.fingerprintOf(DATA), DATA.length);
        assertThat(c.match()).isEqualTo(GateStore.Match.EXACT);
        assertThat(c.replayable()).isTrue();
        assertThat(c.message()).contains("exactly the cells");
        assertThat(c.message()).doesNotContain("WARNING");
    }

    @Test
    void differentAxisColumnsAreRefusedOutright() {
        // Not a warning: the numbers under the polygon are different quantities,
        // so there is no reading of the gate that means anything here.
        GateSet saved = setOn(BIAXIAL, DATA);
        GateSet.Axes other = new GateSet.Axes(GateSet.MODE_BIAXIAL, "biaxial",
                "CD3: Mean", "FoxP3: Mean", "4 image(s)");
        GateStore.Compatibility c = GateStore.check(
                saved, other, GateSet.fingerprintOf(DATA), DATA.length);
        assertThat(c.match()).isEqualTo(GateStore.Match.DIFFERENT_COLUMNS);
        assertThat(c.replayable()).isFalse();
        assertThat(c.message()).contains("CD8: Mean").contains("FoxP3: Mean")
                .contains("will not be loaded");
    }

    @Test
    void anEmbeddingGateOnADifferentLayoutIsAllowedButLoudlyWarned() {
        // The failure the user cannot see, so the message has to carry it.
        GateSet saved = setOn(UMAP, DATA);
        double[][] rerun = {{5, 5}, {4, 4}, {3, 3}, {-1, -1}, {0, 7}};
        GateStore.Compatibility c = GateStore.check(
                saved, UMAP, GateSet.fingerprintOf(rerun), rerun.length);
        assertThat(c.match()).isEqualTo(GateStore.Match.SAME_COLUMNS);
        assertThat(c.replayable()).isTrue();
        assertThat(c.message()).startsWith("WARNING");
        assertThat(c.message()).contains("rotate, reflect or rescale");
        assertThat(c.message()).contains("same seed");
        assertThat(c.message()).contains("different set of cells");
    }

    @Test
    void aBiaxialGateOnOtherCellsIsTheOrdinaryUsefulCase() {
        // Same two measurements, different cells: that is replay working, and
        // the message must not read like a problem.
        GateSet saved = setOn(BIAXIAL, DATA);
        double[][] otherCells = {{1, 1}, {2, 2}, {7, 7}};
        GateStore.Compatibility c = GateStore.check(
                saved, BIAXIAL, GateSet.fingerprintOf(otherCells), otherCells.length);
        assertThat(c.match()).isEqualTo(GateStore.Match.SAME_COLUMNS);
        assertThat(c.replayable()).isTrue();
        assertThat(c.message()).doesNotContain("WARNING");
        assertThat(c.message()).contains("same thing here");
        assertThat(c.message()).contains("5 cells then, 3 now");
    }

    @Test
    void anEmbeddingGateIsNotAcceptedOntoABiaxialPlotWithTheSameColumnNames() {
        // Contrived but possible: measurements literally called UMAP1 / UMAP2.
        // The axis MODE is part of the identity, so the mismatch is caught.
        GateSet saved = setOn(UMAP, DATA);
        GateSet.Axes spoof = new GateSet.Axes(GateSet.MODE_BIAXIAL, "biaxial",
                "UMAP1", "UMAP2", "4 image(s)");
        assertThat(GateStore.check(saved, spoof, GateSet.fingerprintOf(DATA), DATA.length)
                .match()).isEqualTo(GateStore.Match.DIFFERENT_COLUMNS);
    }

    @Test
    void aMissingFingerprintIsTreatedAsUnknownRatherThanAsAMatch(@TempDir Path dir)
            throws IOException {
        // An older file, or one hand-edited. "Unknown" must not read as "fine".
        GateSet saved = new GateSet(UMAP, 5, null, "0.21.0", "now",
                List.of(squareGate("A")));
        GateStore.Compatibility c = GateStore.check(
                saved, UMAP, GateSet.fingerprintOf(DATA), DATA.length);
        assertThat(c.match()).isEqualTo(GateStore.Match.SAME_COLUMNS);
        assertThat(c.message()).startsWith("WARNING");
        assertThat(c.message()).contains("no coordinate fingerprint");
    }

    // ---- replay ----

    @Test
    void indicesInFindsTheEnclosedCellsAndNothingElse() {
        int[] hits = GateStore.indicesIn(squareGate("A"), DATA);
        assertThat(hits).containsExactly(0, 1, 2);
    }

    @Test
    void replayOfADegenerateOrAbsentGateSelectsNothingRatherThanThrowing() {
        assertThat(GateStore.indicesIn(null, DATA)).isEmpty();
        assertThat(GateStore.indicesIn(squareGate("A"), null)).isEmpty();
        assertThat(GateStore.indicesIn(
                new GateSet.Gate("line", new double[][] {{0, 0}, {1, 1}}, 0), DATA)).isEmpty();
    }

    // ---- file naming ----

    @Test
    void aGateFileNameIsFilesystemSafeAndKeepsItsExtension() {
        assertThat(GateStore.fileNameFor("CD8+ / exhausted?"))
                .endsWith(GateStore.EXTENSION)
                .doesNotContain("/")
                .doesNotContain("?");
        assertThat(GateStore.fileNameFor(null)).isEqualTo("gates" + GateStore.EXTENSION);
        assertThat(GateStore.fileNameFor("  ")).isEqualTo("gates" + GateStore.EXTENSION);
        // Not doubled when the caller already supplied it.
        assertThat(GateStore.fileNameFor("a" + GateStore.EXTENSION))
                .isEqualTo("a" + GateStore.EXTENSION);
    }

    @Test
    void describeAxesSaysWhatAUserWouldRecognise() {
        assertThat(GateStore.describeAxes(UMAP)).isEqualTo("the UMAP embedding");
        assertThat(GateStore.describeAxes(BIAXIAL))
                .isEqualTo("'CD3: Mean' against 'CD8: Mean'");
    }
}
