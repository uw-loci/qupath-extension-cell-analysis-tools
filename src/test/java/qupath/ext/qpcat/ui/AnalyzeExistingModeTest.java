package qupath.ext.qpcat.ui;

import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import qupath.ext.qpcat.model.ClusteringConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "analyze current classifications" run mode.
 *
 * <p>A sub-cluster result contains only the sub-clustered population, so it can
 * never show Cluster 0 and 2 alongside Cluster 1.0..N -- those cells were not in
 * the extraction. The combined labelling lives on the objects, and this mode is
 * what turns it into a results window.
 */
class AnalyzeExistingModeTest {

    @Test
    void theModesAreDistinctAndNamed() {
        // A second boolean beside subclusterParentClass would give four states,
        // one of them meaningless. The enum is what keeps that from happening.
        assertThat(ClusteringDialog.RunMode.values()).containsExactlyInAnyOrder(
                ClusteringDialog.RunMode.CLUSTER,
                ClusteringDialog.RunMode.SUBCLUSTER,
                ClusteringDialog.RunMode.ANALYZE_EXISTING);
    }

    @Test
    void existingIsAnAlgorithmIdButNotNone() {
        // Deliberately not reusing "none": that id sets embedding_only in the
        // Python, which switches off marker ranking, PAGA and every plot --
        // exactly what this mode exists to produce.
        assertThat(ClusteringConfig.Algorithm.EXISTING.getId()).isEqualTo("existing");
        assertThat(ClusteringConfig.Algorithm.EXISTING.getId())
                .isNotEqualTo(ClusteringConfig.Algorithm.NONE.getId());
    }

    @Test
    void eachModeStoresItsSettingsSomewhereOfItsOwn() {
        // One shared slot is why this mode remembered nothing: a normal run
        // would overwrite it with an algorithm and a scope chosen for a
        // different question, and analyze-existing would push EXISTING back.
        var keys = new java.util.HashSet<String>();
        for (ClusteringDialog.RunMode m : ClusteringDialog.RunMode.values()) {
            assertThat(m.settingsKey()).isNotBlank();
            keys.add(m.settingsKey());
        }
        assertThat(keys).hasSize(ClusteringDialog.RunMode.values().length);
    }

    @Test
    void theNormalRunKeepsTheSlotItAlreadyHad() {
        // Changing this string would silently discard settings every existing
        // user has already stored under it.
        assertThat(ClusteringDialog.RunMode.CLUSTER.settingsKey()).isEqualTo("cluster");
    }

    @Test
    void aSpecificImageScopeSurvivesARoundTripThroughTheConfig() {
        // The scope was not stored at all, so "Specific images..." minus one
        // image had to be re-picked every single time the dialog opened.
        ClusteringConfig config = new ClusteringConfig();
        config.setClusterEntireProject(true);
        config.setScopeImageNames(java.util.List.of("tme_01.tiff", "tme_02.tiff"));
        assertThat(config.getScopeImageNames()).containsExactly("tme_01.tiff", "tme_02.tiff");

        // Empty names is how "not a subset" is encoded -- clusterEntireProject
        // then says which of the other two scopes it was, so the scope has one
        // representation rather than two that can disagree.
        ClusteringConfig wholeProject = new ClusteringConfig();
        wholeProject.setClusterEntireProject(true);
        wholeProject.setScopeImageNames(java.util.List.of());
        assertThat(wholeProject.getScopeImageNames()).isEmpty();
        assertThat(wholeProject.isClusterEntireProject()).isTrue();
    }

    @Test
    void theMenuEntryExists() {
        // The engine shipped without a menu entry for several releases; the string
        // being present is what makes the feature reachable at all.
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpcat.ui.strings");
        assertThat(res.getString("menu.analyzeExisting")).contains("classification");
    }
}
