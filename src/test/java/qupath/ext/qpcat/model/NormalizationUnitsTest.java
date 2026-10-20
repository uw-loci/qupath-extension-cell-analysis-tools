package qupath.ext.qpcat.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java heatmap names the same units as the Python figures' colour scales; both
 * read normalization_units.json.
 */
class NormalizationUnitsTest {

    @Test
    void everyNormalizationNamesTheSharedUnits() throws Exception {
        Map<String, String> shared = new TreeMap<>();
        try (InputStream in = getClass().getResourceAsStream(
                "/qupath/ext/qpcat/normalization_units.json")) {
            assertThat(in).isNotNull();
            JsonObject units = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("units");
            units.entrySet().forEach(e -> shared.put(e.getKey(), e.getValue().getAsString()));
        }
        Map<String, String> enumUnits = new TreeMap<>();
        for (ClusteringConfig.Normalization n : ClusteringConfig.Normalization.values()) {
            enumUnits.put(n.getId(), n.getUnits());
        }
        assertThat(enumUnits).isEqualTo(shared);
    }

    @Test
    void anUnknownNormalizationClaimsNoUnits() {
        assertThat(ClusteringConfig.Normalization.unitsOf("zscore")).isEqualTo("z-score");
        assertThat(ClusteringConfig.Normalization.unitsOf(null)).isEmpty();
        assertThat(ClusteringConfig.Normalization.unitsOf("something-old")).isEmpty();
    }
}
