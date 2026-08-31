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
    void sizeOfIsBestEffortAndNeverThrows(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a"), "12345");
        assertThat(ApposeEnvLocation.sizeOf(tmp)).isEqualTo(5);
        assertThat(ApposeEnvLocation.sizeOf(tmp.resolve("does-not-exist"))).isZero();
    }
}
