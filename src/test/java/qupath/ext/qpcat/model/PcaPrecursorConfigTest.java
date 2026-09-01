package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PCA precursor CHANGES CLUSTER LABELS, so whether it ran has to survive a
 * config round-trip honestly -- including for configs written before the option
 * existed.
 *
 * <p>These pin the reason the field is a nullable {@link Boolean} rather than a
 * primitive: Gson leaves an absent field at whatever the constructor set, so a
 * primitive defaulting to {@code true} would silently switch the precursor ON
 * for every config saved before it existed. RUN_INFO.txt tells users that
 * loading a saved config reproduces the run; a primitive default would make that
 * statement false, and nothing would report it.
 */
class PcaPrecursorConfigTest {

    private static final Gson GSON = new Gson();

    @Test
    void aConfigWrittenBeforeTheOptionExistedReadsAsOff() {
        // No "pcaPrecursor" key at all -- what every pre-existing saved config
        // and pre-existing YAML looks like on disk.
        String legacyJson = "{\"topNMarkers\":5,\"enableSpatialSmoothing\":false}";
        ClusteringConfig config = GSON.fromJson(legacyJson, ClusteringConfig.class);

        assertThat(config.isPcaPrecursor())
                .as("an old config must reproduce its original clusters")
                .isFalse();
        assertThat(config.hasPcaPrecursorChoice())
                .as("and must be distinguishable from one that chose 'off'")
                .isFalse();
    }

    @Test
    void anExplicitChoiceRoundTripsBothWays() {
        ClusteringConfig on = new ClusteringConfig();
        on.setPcaPrecursor(true);
        ClusteringConfig reloadedOn =
                GSON.fromJson(GSON.toJson(on), ClusteringConfig.class);
        assertThat(reloadedOn.isPcaPrecursor()).isTrue();
        assertThat(reloadedOn.hasPcaPrecursorChoice()).isTrue();

        ClusteringConfig off = new ClusteringConfig();
        off.setPcaPrecursor(false);
        ClusteringConfig reloadedOff =
                GSON.fromJson(GSON.toJson(off), ClusteringConfig.class);
        assertThat(reloadedOff.isPcaPrecursor()).isFalse();
        assertThat(reloadedOff.hasPcaPrecursorChoice())
                .as("an explicit 'off' is a recorded choice, not an absent one")
                .isTrue();
    }

    @Test
    void aFreshConfigRecordsNoChoiceUntilTheDialogSetsOne() {
        // The dialog ticks the box and calls setPcaPrecursor for a new run; the
        // bare object must not pretend a choice was made.
        assertThat(new ClusteringConfig().hasPcaPrecursorChoice()).isFalse();
        assertThat(new ClusteringConfig().isPcaPrecursor()).isFalse();
    }
}
