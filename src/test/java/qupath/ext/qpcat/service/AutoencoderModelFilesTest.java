package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Model metadata is real JSON both ways, and files from before 0.26.0 still read (H35). */
class AutoencoderModelFilesTest {

    @Test
    void namesWithQuotesAndBackslashesAndExponentNumbersRoundTrip(@TempDir Path dir)
            throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("input_mode", "tiles");
        meta.put("tile_size", 48);
        meta.put("downsample", 2.5);
        meta.put("include_mask", false);
        meta.put("cells_only", true);
        meta.put("class_names", List.of("T \"helper\" cell", "B\\cell", "plain"));
        meta.put("measurements", List.of("Nucleus: \"CD3\" mean", "C:\\path"));
        meta.put("learning_rate", 1.0e-4);
        meta.put("min_delta", -2.5e-7);
        meta.put("final_accuracy", Double.NaN);
        Path file = dir.resolve("m.json");

        AutoencoderModelFiles.writeMetadata(file, meta);
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("NaN");   // strict JSON: written as null
        assertThat(json).contains("1.0E-4");

        AutoencoderModelFiles.Metadata back = AutoencoderModelFiles.readMetadata(file);
        assertThat(back.inputMode()).isEqualTo("tiles");
        assertThat(back.tileSize()).isEqualTo(48);
        assertThat(back.downsample()).isEqualTo(2.5);
        assertThat(back.includeMask()).isFalse();
        assertThat(back.cellsOnly()).isTrue();
        assertThat(back.classNames()).containsExactly("T \"helper\" cell", "B\\cell", "plain");
        assertThat(back.measurements()).containsExactly("Nucleus: \"CD3\" mean", "C:\\path");
    }

    @Test
    void aFileWrittenByTheOldHandBuiltWriterStillReads(@TempDir Path dir) throws IOException {
        // Exactly what QP-CAT wrote before 0.26.0 for names without quotes or backslashes:
        // numbers via toString (so 1.0E-4 and NaN appear), lists of strings.
        String old = "{\n"
                + "  \"input_mode\": \"measurements\",\n"
                + "  \"tile_size\": 64,\n"
                + "  \"downsample\": 1.0E-4,\n"
                + "  \"include_mask\": true,\n"
                + "  \"cells_only\": false,\n"
                + "  \"class_names\": [\"Tumor\", \"Stroma\"],\n"
                + "  \"measurements\": [\"Nucleus: CD3 mean\"],\n"
                + "  \"final_accuracy\": NaN,\n"
                + "  \"learning_rate\": -1.5E-3\n"
                + "}";
        Path file = dir.resolve("old.json");
        Files.writeString(file, old);
        AutoencoderModelFiles.Metadata back = AutoencoderModelFiles.readMetadata(file);
        // The regex reader read 1.0E-4 as 1.0.
        assertThat(back.downsample()).isEqualTo(1.0e-4);
        assertThat(back.classNames()).containsExactly("Tumor", "Stroma");
        assertThat(back.measurements()).containsExactly("Nucleus: CD3 mean");
    }

    @Test
    void aDamagedOldFileSaysWhatHappened(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{ \"class_names\": [\"T \"helper\" cell\"] }");
        assertThatThrownBy(() -> AutoencoderModelFiles.readMetadata(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("retrain");
    }

    @Test
    void modelNamesAreSanitizedNotFlattenedAndTakenNamesAreFound(@TempDir Path dir)
            throws IOException {
        // The old rule mapped every non-ASCII-alphanumeric to "_": "model v1", "model_v1"
        // and "model/v1" all became "model_v1".
        assertThat(AutoencoderModelFiles.stemFor("model v1")).isEqualTo("model v1");
        assertThat(AutoencoderModelFiles.stemFor("model/v1")).isEqualTo("model_v1");
        Files.writeString(dir.resolve("model v1.b64"), "x");
        assertThat(AutoencoderModelFiles.exists(dir, "model v1")).isTrue();
        assertThat(AutoencoderModelFiles.freeStem(dir, "model v1")).isEqualTo("model v1_2");
        assertThat(AutoencoderModelFiles.exists(dir, "other")).isFalse();
    }

    @Test
    void aNonFiniteDownsampleIsRefused(@TempDir Path dir) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("downsample", Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> AutoencoderModelFiles.writeMetadata(dir.resolve("m.json"), meta))
                .hasMessageContaining("downsample");
    }
}
