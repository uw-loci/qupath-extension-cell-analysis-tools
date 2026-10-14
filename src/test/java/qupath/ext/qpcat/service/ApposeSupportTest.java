/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.qpcat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * COPIED BETWEEN REPOS together with {@link ApposeSupport}; see
 * {@code tools/check_appose_copies.py}.
 */
class ApposeSupportTest {

    private static final String TOML = "[project]\nname = \"x\"\n";
    private static final String LOCK = "version: 6\npackages: []\n";

    @TempDir
    Path tmp;

    private Path env() throws IOException {
        Path env = tmp.resolve("env");
        Files.createDirectories(env);
        return env;
    }

    private static void buildMarker(Path env) throws IOException {
        Files.createDirectories(env.resolve(".pixi").resolve("envs"));
        Files.writeString(env.resolve(".pixi").resolve("marker"), "built");
    }

    @Test
    void firstBuildStagesOnlyTheLock() throws IOException {
        Path env = env();
        assertFalse(ApposeSupport.syncManifest(env, TOML, LOCK, null));
        assertFalse(Files.exists(env.resolve("pixi.toml")));
        assertEquals(LOCK, Files.readString(env.resolve("pixi.lock")));
    }

    @Test
    void unchangedManifestAndLockKeepTheEnvironment() throws IOException {
        Path env = env();
        Files.writeString(env.resolve("pixi.toml"), TOML.replace("\n", "\r\n"));
        Files.writeString(env.resolve("pixi.lock"), LOCK + "\n");
        buildMarker(env);
        assertFalse(ApposeSupport.syncManifest(env, TOML, LOCK, null));
        assertTrue(Files.exists(env.resolve(".pixi").resolve("marker")));
    }

    @Test
    void missingLockWithUnchangedManifestIsRestagedWithoutARebuild() throws IOException {
        Path env = env();
        Files.writeString(env.resolve("pixi.toml"), TOML);
        buildMarker(env);
        assertFalse(ApposeSupport.syncManifest(env, TOML, LOCK, null));
        assertEquals(LOCK, Files.readString(env.resolve("pixi.lock")));
        assertTrue(Files.exists(env.resolve(".pixi").resolve("marker")));
    }

    @Test
    void aLockWrittenByANewerPixiIsReplacedWithoutARebuild() throws IOException {
        Path env = env();
        Files.writeString(env.resolve("pixi.toml"), TOML);
        Files.writeString(env.resolve("pixi.lock"), "version: 7\npackages: []\n");
        buildMarker(env);
        assertFalse(ApposeSupport.syncManifest(env, TOML, LOCK, null));
        assertEquals(LOCK, Files.readString(env.resolve("pixi.lock")));
        assertTrue(Files.exists(env.resolve(".pixi").resolve("marker")));
    }

    @Test
    void changedManifestForcesARebuild() throws IOException {
        Path env = env();
        Files.writeString(env.resolve("pixi.toml"), "[project]\nname = \"old\"\n");
        Files.writeString(env.resolve("pixi.lock"), LOCK);
        buildMarker(env);
        assertTrue(ApposeSupport.syncManifest(env, TOML, LOCK, null));
        assertEquals(TOML, Files.readString(env.resolve("pixi.toml")));
        assertEquals(LOCK, Files.readString(env.resolve("pixi.lock")));
        assertFalse(Files.exists(env.resolve(".pixi")));
    }

    @Test
    void changedLockForcesARebuild() throws IOException {
        Path env = env();
        Files.writeString(env.resolve("pixi.toml"), TOML);
        Files.writeString(env.resolve("pixi.lock"), "version: 6\npackages: [old]\n");
        buildMarker(env);
        assertTrue(ApposeSupport.syncManifest(env, TOML, LOCK, null));
        assertFalse(Files.exists(env.resolve(".pixi")));
    }

    @Test
    void lockFormatVersionIsParsed() {
        assertEquals(6, ApposeSupport.lockFormatVersion("version: 6\nenvironments:\n"));
        assertEquals(7, ApposeSupport.lockFormatVersion("# comment\nversion: 7\n"));
        assertEquals(-1, ApposeSupport.lockFormatVersion("environments:\n  version: 9\n"));
        assertEquals(-1, ApposeSupport.lockFormatVersion(null));
    }

    @Test
    void failureSignaturesAreRecognised() {
        assertTrue(ApposeSupport.isThreadDeath("Thread Death"));
        assertFalse(ApposeSupport.isThreadDeath("boom"));
        assertFalse(ApposeSupport.isThreadDeath((String) null));
        assertTrue(ApposeSupport.isThreadDeath(new IOException("outer", new RuntimeException("thread death"))));
        assertTrue(ApposeSupport.looksLikeWindowsFileLock("failed to link x: os error 32"));
        assertFalse(ApposeSupport.looksLikeWindowsFileLock("os error 32"));
        assertTrue(ApposeSupport.looksLikeLockFormatSkew("lock file version 7, but only up to 6 supported"));
        assertFalse(ApposeSupport.looksLikeLockFormatSkew("no space left on device"));
    }

    @Test
    void causeChainIsCollectedInOrder() {
        String chain = ApposeSupport.collectCauseMessages(
                new IOException("top", new IllegalStateException("middle", new RuntimeException("bottom"))));
        assertEquals("top\nmiddle\nbottom\n", chain);
    }

    @Test
    void windowsAdviceNamesTheRealEnvironment() {
        String advice = ApposeSupport.windowsFileLockAdvice(Path.of("D:", "scratch", "appose", "my-env"));
        assertTrue(advice.contains("my-env"), advice);
        assertFalse(advice.contains("USERPROFILE"), advice);
    }

    @Test
    void protocolEnvelopesAreDetected() {
        assertTrue(ApposeSupport.isProtocolEnvelope("{\"task\": \"1\", \"responseType\": \"UPDATE\"}"));
        assertFalse(ApposeSupport.isProtocolEnvelope("[INFO] loading model"));
        assertFalse(ApposeSupport.isProtocolEnvelope(null));
    }

    @Test
    void loadResourceReadsTestResources() throws IOException {
        String text = ApposeSupport.loadResource("qupath/ext/qpcat/fake_appose_worker.py");
        assertTrue(text.contains("responseType"));
    }

    @Test
    void stopWorkerToleratesNull() {
        ApposeSupport.stopWorker(null, 10, null);
    }

    @Test
    void deleteDirectoryRecursivelyRemovesATree() throws IOException {
        Path dir = tmp.resolve("tree");
        Files.createDirectories(dir.resolve("a").resolve("b"));
        Files.writeString(dir.resolve("a").resolve("b").resolve("f.txt"), "x", StandardCharsets.UTF_8);
        ApposeSupport.deleteDirectoryRecursively(dir);
        assertFalse(Files.exists(dir));
        ApposeSupport.deleteDirectoryRecursively(dir);
    }
}
