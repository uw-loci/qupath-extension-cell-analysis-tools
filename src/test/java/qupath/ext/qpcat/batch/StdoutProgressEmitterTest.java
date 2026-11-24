package qupath.ext.qpcat.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fields of a batch progress row stay on one parseable line whatever an image is called (H57). */
class StdoutProgressEmitterTest {

    @Test
    void plainValuesStayBare() {
        assertThat(StdoutProgressEmitter.formatValue("Patient05_ROI1")).isEqualTo("Patient05_ROI1");
        assertThat(StdoutProgressEmitter.formatValue(3)).isEqualTo("3");
        assertThat(StdoutProgressEmitter.formatValue(null)).isEqualTo("null");
    }

    @Test
    void spacesQuotesBackslashesAndLineBreaksAreQuotedAndEscaped() {
        assertThat(StdoutProgressEmitter.formatValue("ROI 1")).isEqualTo("\"ROI 1\"");
        assertThat(StdoutProgressEmitter.formatValue("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(StdoutProgressEmitter.formatValue("a\nb\tc\r")).isEqualTo("\"a\\nb\\tc\\r\"");
        assertThat(StdoutProgressEmitter.formatValue("C:\\x")).isEqualTo("\"C:\\\\x\"");
        assertThat(StdoutProgressEmitter.formatValue("bell\u0007")).isEqualTo("\"bell\\u0007\"");
        assertThat(StdoutProgressEmitter.formatValue("")).isEqualTo("\"\"");
    }

    @Test
    void aRowWithAnAwkwardImageNameIsOneLine() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        StdoutProgressEmitter emitter = new StdoutProgressEmitter(ps, ps, false);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("image", "slide \"A\"\nsecond line");
        fields.put("step", "clustering");
        emitter.emitRow(ProgressEmitter.Level.INFO, 1, 2, fields, "failed: no cells");
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text.strip().lines()).hasSize(1);
        assertThat(text).contains("image=\"slide \\\"A\\\"\\nsecond line\" step=clustering failed: no cells");
    }
}
