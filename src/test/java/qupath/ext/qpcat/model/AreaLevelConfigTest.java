package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config-level invariants for independent areas.
 * <p>
 * {@link ClusteringConfig} is Gson-serialised wholesale into
 * {@code <name>_config.json} by {@code ClusteringRunRecord}, and reloaded by
 * "Load Config from file...". Every project saved before this feature existed
 * has JSON with no {@code areaLevels} key at all, and must keep loading with
 * the behaviour it was saved under -- one area per image.
 */
class AreaLevelConfigTest {

    private final Gson gson = new Gson();

    @Test
    void aConfigSavedBeforeThisFeatureLoadsAsImagesOnly() {
        // No areaLevels key, exactly as older saves look.
        ClusteringConfig restored = gson.fromJson(
                "{\"algorithm\":\"LEIDEN\",\"spatialGraphK\":15}", ClusteringConfig.class);

        assertThat(restored.getAreaLevels()).hasSize(1);
        assertThat(restored.getAreaLevels().get(0).getLevel()).isEqualTo(AreaLevel.IMAGES);
        // The important consequence: nothing is resolved, nothing is shipped,
        // and the run takes the pre-areas Python path.
        assertThat(restored.hasSubImageAreaLevels()).isFalse();
    }

    @Test
    void anExplicitNullAreaLevelsAlsoLoadsAsImagesOnly() {
        ClusteringConfig restored = gson.fromJson(
                "{\"areaLevels\":null}", ClusteringConfig.class);
        assertThat(restored.getAreaLevels()).hasSize(1);
        assertThat(restored.hasSubImageAreaLevels()).isFalse();
    }

    @Test
    void areaLevelsRoundTripThroughGson() {
        ClusteringConfig original = new ClusteringConfig();
        original.setAreaLevels(List.of(
                new AreaLevelSpec(AreaLevel.IMAGES),
                new AreaLevelSpec(AreaLevel.TMA_CORES),
                new AreaLevelSpec(AreaLevel.ANNOTATIONS, List.of("Tissue", "Region"))));

        ClusteringConfig restored =
                gson.fromJson(gson.toJson(original), ClusteringConfig.class);

        assertThat(restored.getAreaLevels()).hasSize(3);
        assertThat(restored.getAreaLevels().get(1).getLevel()).isEqualTo(AreaLevel.TMA_CORES);
        assertThat(restored.getAreaLevels().get(2).getAnnotationClasses())
                .containsExactly("Tissue", "Region");
        assertThat(restored.hasSubImageAreaLevels()).isTrue();
    }

    @Test
    void imagesOnlyIsNotTreatedAsASubImageSplit() {
        ClusteringConfig config = new ClusteringConfig();
        config.setAreaLevels(List.of(new AreaLevelSpec(AreaLevel.IMAGES)));
        assertThat(config.hasSubImageAreaLevels()).isFalse();
    }

    @Test
    void anEmptyLevelListIsTreatedAsImagesOnlyRatherThanNoGrouping() {
        ClusteringConfig config = new ClusteringConfig();
        config.setAreaLevels(List.of());
        assertThat(config.getAreaLevels()).hasSize(1);
        assertThat(config.getAreaLevels().get(0).getLevel()).isEqualTo(AreaLevel.IMAGES);
    }

    @Test
    void batchKeyDefaultsToImagesSoExistingRunsAreUnchanged() {
        assertThat(new ClusteringConfig().getBatchKey())
                .isEqualTo(ClusteringConfig.BATCH_KEY_IMAGES);
        assertThat(gson.fromJson("{}", ClusteringConfig.class).getBatchKey())
                .isEqualTo(ClusteringConfig.BATCH_KEY_IMAGES);
    }

    @Test
    void batchKeyAcceptsAreasAndRejectsAnythingElse() {
        ClusteringConfig config = new ClusteringConfig();
        config.setBatchKey("areas");
        assertThat(config.getBatchKey()).isEqualTo(ClusteringConfig.BATCH_KEY_AREAS);

        // A typo must not silently become a third, undefined mode.
        config.setBatchKey("per-core");
        assertThat(config.getBatchKey()).isEqualTo(ClusteringConfig.BATCH_KEY_IMAGES);
        config.setBatchKey(null);
        assertThat(config.getBatchKey()).isEqualTo(ClusteringConfig.BATCH_KEY_IMAGES);
    }

    @Test
    void areaLevelIdsAreStableBecauseTheyArePersisted() {
        // These strings live in saved configs and YAML batch files.
        assertThat(AreaLevel.IMAGES.getId()).isEqualTo("images");
        assertThat(AreaLevel.TMA_CORES.getId()).isEqualTo("tma_cores");
        assertThat(AreaLevel.ANNOTATIONS.getId()).isEqualTo("annotations");
        assertThat(AreaLevel.fromId("tma_cores")).isEqualTo(AreaLevel.TMA_CORES);
        assertThat(AreaLevel.fromId("TMA_CORES")).isEqualTo(AreaLevel.TMA_CORES);
        // Unknown ids fall back to the safe level rather than throwing, so a
        // hand-edited file degrades to "no split" instead of failing the run.
        assertThat(AreaLevel.fromId("nonsense")).isEqualTo(AreaLevel.IMAGES);
        assertThat(AreaLevel.fromId(null)).isEqualTo(AreaLevel.IMAGES);
    }

    @Test
    void annotationClassesAreDefensivelyCopied() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("Tissue"));
        AreaLevelSpec spec = new AreaLevelSpec(AreaLevel.ANNOTATIONS, mutable);
        mutable.add("Tumor");
        assertThat(spec.getAnnotationClasses()).containsExactly("Tissue");
    }
}
