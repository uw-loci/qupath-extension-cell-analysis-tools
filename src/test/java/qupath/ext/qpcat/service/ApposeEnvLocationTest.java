package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the Appose environment goes, and what is safe to delete.
 *
 * <p>The deletion half is the reason this is tested rather than eyeballed: it
 * removes multi-gigabyte directories, on machines where the location has been
 * pointed at shared or project storage. Every uncertain case must resolve to
 * KEEP, because that is the one that can be undone.
 */
class ApposeEnvLocationTest {

    @Test
    void blankPreferenceMeansTheApposeDefault() {
        Path p = ApposeEnvLocation.resolve(null, "qupath-qpcat");
        assertThat(p).isEqualTo(Path.of(System.getProperty("user.home"),
                ".local", "share", "appose", "qupath-qpcat"));
        assertThat(ApposeEnvLocation.resolve("   ", "qupath-qpcat")).isEqualTo(p);
    }

    @Test
    void aConfiguredBaseIsUsedAndTheEnvNameIsStillAppended() {
        // The env name must remain a subdirectory: pointing two variants at one
        // base must not have them share a directory.
        assertThat(ApposeEnvLocation.resolve("/scratch/me", "qupath-qpcat"))
                .isEqualTo(Path.of("/scratch/me", "qupath-qpcat"));
        assertThat(ApposeEnvLocation.resolve("/scratch/me", "qupath-qpcat-gpu"))
                .isEqualTo(Path.of("/scratch/me", "qupath-qpcat-gpu"));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        // A path pasted from a terminal usually carries a trailing space.
        assertThat(ApposeEnvLocation.resolve("  /scratch/me  ", "env"))
                .isEqualTo(Path.of("/scratch/me", "env"));
    }

    @Test
    void anEmptyDirectoryIsNotABuiltEnvironment(@TempDir Path tmp) throws Exception {
        Path env = Files.createDirectory(tmp.resolve("env"));
        assertThat(ApposeEnvLocation.isBuilt(env))
                .as("a bare directory must not read as an environment -- otherwise "
                        + "cleanup would offer to delete an unrelated folder")
                .isFalse();
        Files.createDirectory(env.resolve(".pixi"));
        assertThat(ApposeEnvLocation.isBuilt(env)).isTrue();
    }

    @Test
    void cleanupDeclinesWhenNothingWasSuperseded(@TempDir Path tmp) throws Exception {
        Path env = Files.createDirectories(tmp.resolve("env/.pixi")).getParent();
        // Same path old and new: the location did not actually change.
        assertThat(ApposeEnvLocation.promptCleanup(env, env)).isFalse();
        assertThat(env).exists();
    }

    @Test
    void cleanupDeclinesForAPathThatIsNotAnEnvironment(@TempDir Path tmp) throws Exception {
        Path notEnv = Files.createDirectory(tmp.resolve("random-folder"));
        Files.writeString(notEnv.resolve("important.txt"), "do not delete me");
        assertThat(ApposeEnvLocation.promptCleanup(notEnv, tmp.resolve("new"))).isFalse();
        assertThat(notEnv.resolve("important.txt")).exists();
    }

    @Test
    void cleanupDeclinesForNull() {
        assertThat(ApposeEnvLocation.promptCleanup(null, Path.of("/tmp/new"))).isFalse();
    }

    @Test
    void theCleanupIsActuallyWiredToAVerifiedBuild() throws Exception {
        // promptCleanup existed but nothing called it -- the helper was tested
        // and the feature did not exist. Guard the wiring, not just the helper.
        String svc = Files.readString(Path.of(
                "src/main/java/qupath/ext/qpcat/service/ApposeClusteringService.java"));
        int call = svc.indexOf("offerPreviousEnvCleanup();");
        assertThat(call).as("cleanup must be invoked, not merely defined").isGreaterThan(0);
        int verified = svc.indexOf("QPCAT Appose service initialized");
        assertThat(verified).isGreaterThan(0);
        assertThat(call)
                .as("cleanup must come AFTER the build verifies -- offering to delete the "
                        + "only working environment before the replacement is proven is the "
                        + "one outcome that cannot be undone")
                .isGreaterThan(verified);
    }

    @Test
    void theSupersededPathIsRecordedOnlyAfterAVerifiedBuild() throws Exception {
        String svc = Files.readString(Path.of(
                "src/main/java/qupath/ext/qpcat/service/ApposeClusteringService.java"));
        int at = svc.indexOf("private void offerPreviousEnvCleanup");
        String body = svc.substring(at, svc.indexOf("\n    }", at));
        // Without recording the old path, changing the preference loses it and
        // nothing can know which directory was superseded.
        assertThat(body).contains("setEnvLastBuiltDir");
        assertThat(body)
                .as("the record must update whether or not the user deletes -- the question "
                        + "is asked once, and declining means keep, not ask again every launch")
                .satisfies(b -> assertThat(b.indexOf("setEnvLastBuiltDir"))
                        .isLessThan(b.indexOf("promptCleanup")));
    }

    @Test
    void sizeOfIsBestEffortAndNeverThrows(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a"), "12345");
        assertThat(ApposeEnvLocation.sizeOf(tmp)).isEqualTo(5);
        assertThat(ApposeEnvLocation.sizeOf(tmp.resolve("does-not-exist"))).isZero();
    }
}
