package qupath.ext.qpcat.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the bold marker sits in a measurement name. The case list is shared with
 * python_tests/test_measurement_name_labels.py, so a figure and a table never
 * disagree about which word is the marker.
 */
class MeasurementNamesTest {

    private static List<String[]> cases() throws Exception {
        try (InputStream in = MeasurementNamesTest.class.getResourceAsStream(
                "/qupath/ext/qpcat/measurement_name_cases.json")) {
            assertThat(in).as("shared fixture on the test classpath").isNotNull();
            JsonArray rows = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("cases");
            List<String[]> out = new ArrayList<>();
            for (JsonElement row : rows) {
                JsonArray r = row.getAsJsonArray();
                out.add(new String[] {r.get(0).getAsString(), r.get(1).getAsString(),
                        r.get(2).getAsString(), r.get(3).getAsString()});
            }
            return out;
        }
    }

    @Test
    void splitsEverySharedCaseTheWayThePythonSideDoes() throws Exception {
        List<String[]> cases = cases();
        assertThat(cases).hasSizeGreaterThan(20);
        for (String[] c : cases) {
            assertThat(MeasurementNames.split(c[0]))
                    .as(c[0])
                    .isEqualTo(new MeasurementNames.Parts(c[1], c[2], c[3]));
        }
    }

    @Test
    void nullIsAnEmptyName() {
        assertThat(MeasurementNames.split(null)).isEqualTo(new MeasurementNames.Parts("", "", ""));
    }

    @Test
    void shortenKeepsTheStatisticAndDropsOnlyAGenericCompartment() {
        assertThat(MeasurementNames.shorten("Cell: PCNA: Mean")).isEqualTo("PCNA Mean");
        assertThat(MeasurementNames.shorten("Cell: PCNA: Median")).isEqualTo("PCNA Median");
        assertThat(MeasurementNames.shorten("Nucleus: CD3 std dev")).isEqualTo("Nucleus CD3 std dev");
        assertThat(MeasurementNames.shorten("Cell: Area")).isEqualTo("Cell Area");
        assertThat(MeasurementNames.shorten("CD3")).isEqualTo("CD3");
        assertThat(MeasurementNames.shorten(null)).isEmpty();
    }

    @Test
    void theShortenedSplitJoinsToExactlyTheShortenedName() throws Exception {
        for (String[] c : cases()) {
            MeasurementNames.Parts p = MeasurementNames.splitShortened(c[0]);
            assertThat(p.text()).as(c[0]).isEqualTo(MeasurementNames.shorten(c[0]));
        }
        assertThat(MeasurementNames.splitShortened("Cell: PCNA: Mean"))
                .isEqualTo(new MeasurementNames.Parts("", "PCNA", " Mean"));
        assertThat(MeasurementNames.splitShortened("Nucleus: CD3 std dev"))
                .isEqualTo(new MeasurementNames.Parts("Nucleus ", "CD3", " std dev"));
        assertThat(MeasurementNames.splitShortened("CD3: Nucleus: Mean"))
                .isEqualTo(new MeasurementNames.Parts("", "CD3", " Nucleus Mean"));
    }

    @Test
    void aShortenedNameThatCannotBeLinedUpIsShownWithNothingBold() {
        // The extra spaces survive split() but shorten() trims them away.
        MeasurementNames.Parts p = MeasurementNames.splitShortened("Cell:  CD3 :  Mean ");
        assertThat(p.text()).isEqualTo(MeasurementNames.shorten("Cell:  CD3 :  Mean "));
        assertThat(p.marker()).isEmpty();
    }
}
