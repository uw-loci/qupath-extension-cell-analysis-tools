package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.ClusteringConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "We record everything in the metadata" for a run restricted by classification.
 *
 * <p>A subset changes WHICH CELLS were clustered, so two results can differ for a
 * reason no parameter explains. The record has to make that visible, and the
 * saved config has to carry it so the run reloads as the same run.
 */
class ClassSubsetRunInfoTest {

    private static String runInfo(Path dir, ClusteringConfig config) throws Exception {
        ClusteringRunRecord.write(dir, "res", config, null, "Current image");
        return Files.readString(dir.resolve("res_RUN_INFO.txt"));
    }

    @Test
    void theSubsetIsStatedBesideTheScope(@TempDir Path dir) throws Exception {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of("Tumor", "Stroma"));

        String info = runInfo(dir, config);

        assertThat(info).contains("Cell subset : classifications Tumor, Stroma");
        // Beside the scope, not buried in the parameters: a reader comparing two
        // results must see immediately that they covered different cells.
        assertThat(info.indexOf("Cell subset")).isLessThan(info.indexOf("Parameters"));
    }

    @Test
    void unclassifiedBeingIncludedIsPartOfTheRecord(@TempDir Path dir) throws Exception {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of("Tumor"));
        config.setIncludeUnclassified(true);

        assertThat(runInfo(dir, config)).contains("Tumor plus unclassified");
    }

    @Test
    void anUnrestrictedRunSaysNothingAboutSubsets(@TempDir Path dir) throws Exception {
        assertThat(runInfo(dir, new ClusteringConfig())).doesNotContain("Cell subset");
    }

    @Test
    void aGraphBuiltOverTheSubsetSaysSo(@TempDir Path dir) throws Exception {
        // BANKSY and smoothing are allowed on a subset precisely because the
        // record states what the graph spanned; without the note the permission
        // is not defensible.
        ClusteringConfig banksy = new ClusteringConfig();
        banksy.setIncludedClasses(List.of("Tumor"));
        banksy.setAlgorithm(ClusteringConfig.Algorithm.BANKSY);
        assertThat(runInfo(dir, banksy)).contains("the spatial graph spans ONLY the subset");

        ClusteringConfig smoothing = new ClusteringConfig();
        smoothing.setIncludedClasses(List.of("Tumor"));
        smoothing.setEnableSpatialSmoothing(true);
        assertThat(runInfo(dir, smoothing)).contains("the spatial graph spans ONLY the subset");

        ClusteringConfig plain = new ClusteringConfig();
        plain.setIncludedClasses(List.of("Tumor"));
        assertThat(runInfo(dir, plain)).doesNotContain("spans ONLY the subset");
    }

    @Test
    void theSavedConfigCarriesTheSubsetSoTheRunReloadsAsItself(@TempDir Path dir)
            throws Exception {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of("Tumor"));
        config.setIncludeUnclassified(true);
        ClusteringRunRecord.write(dir, "res", config, null, "Current image");

        ClusteringConfig reloaded = new Gson().fromJson(
                Files.readString(dir.resolve("res_config.json")), ClusteringConfig.class);

        assertThat(reloaded.getIncludedClasses()).containsExactly("Tumor");
        assertThat(reloaded.isIncludeUnclassified()).isTrue();
        assertThat(reloaded.isClassSubsetActive()).isTrue();
    }

    @Test
    void aConfigWrittenBeforeThisOptionExistedReadsAsUnrestricted() {
        // The reason includedClasses is nullable. Gson leaves an absent field at
        // its initialised value, so a primitive-style default of "empty list"
        // would turn every older config into a run over no cells.
        ClusteringConfig old = new Gson().fromJson(
                "{\"algorithm\":\"LEIDEN\",\"normalization\":\"ZSCORE\"}",
                ClusteringConfig.class);

        assertThat(old.getIncludedClasses()).isNull();
        assertThat(old.isClassSubsetActive()).isFalse();
        assertThat(old.isClassSubsetEmpty()).isFalse();
    }
}
