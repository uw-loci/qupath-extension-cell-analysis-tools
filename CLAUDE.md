# CLAUDE.md -- QP-CAT (qupath-extension-cell-analysis-tools)

Apache-2.0 QuPath 0.7 extension (Java + Appose/Python). This file records repo-specific build
notes; see `README.md` for the full feature set and `THIRD_PARTY_NOTICES.md` for licensing.

## Build order (cluster3d-core publishToMavenLocal FIRST)

QP-CAT depends on the Apache-2.0 shared 3D-viewer library `cluster3d-core`
(`io.github.uw-loci:cluster3d-core:0.1.0`) for its **"3D View"** results tab. Publish that
library to Maven Local before building QP-CAT:

```bash
cd ../cluster3d-core
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 publishToMavenLocal test
cd ../qupath-extension-cell-analysis-tools
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 shadowJar
```

- The dependency is a **non-transitive `implementation`**:
  `implementation("io.github.uw-loci:cluster3d-core:0.1.0") { isTransitive = false }`. It gets
  SHADED into the `-all.jar` (its own code); QuPath + JavaFX are host-provided, so `isTransitive
  = false` keeps them out of the bundle (core's published POM lists them because qupath-conventions
  injects them). Confirm the shaded classes with:
  `unzip -l build/libs/*-all.jar | grep qupath/ext/cluster3d/`.
- A user with BOTH QP-CAT and the standalone `qupath-extension-cluster-3d-navigator` installed
  has `cluster3d-core` shaded into both jars at the same pinned version 0.1.0 -> identical
  bytecode, harmless.

## The "3D View" tab

`ClusteringDialog.showResultsDialog(...)` builds the results `TabPane`. Next to the interactive
2D embedding scatter tab, a **"3D View"** tab hosts `qupath.ext.cluster3d.ui.Cluster3DNavigatorPane`
(from core). It is built **lazily** on first tab selection (the pane reads the clustered images'
detections + PathClass + `UMAP1/2/3`-style measurements generically -- no in-memory result
plumbing), and `pane.dispose()` is called via a stacked `WINDOW_HIDDEN` handler when the results
window closes.

The tab calls `pane.initializeForHost(clusteredEntries)` (NOT `initialLoad()`): the host owns the
scope, so the pane hides its Mode radios + "Select images..." button and reads exactly the
clustered images with **no picker prompt**. The clustered scope is threaded from `runClustering`
through a nullable `clusteredEntries` param on the full `showResultsDialog(...)`; the "View Past
Results" and external (SpatialStats*) callers pass `null` -> the tab reads the current image only,
still without a picker. The standalone navigator keeps using `initialLoad()` + its picker.

## Conventions

- ASCII-only in logs / internal strings (Windows cp1252). Use `qupath.fx.dialogs.Dialogs`.
- No `_legacy` / parallel code paths.
