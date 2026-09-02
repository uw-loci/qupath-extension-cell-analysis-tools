# CLAUDE.md -- QP-CAT (qupath-extension-cell-analysis-tools)

Apache-2.0 QuPath 0.7 extension (Java + Appose/Python). This file records repo-specific build
notes; see `README.md` for the full feature set and `THIRD_PARTY_NOTICES.md` for licensing.

## Build order (cluster3d-core publishToMavenLocal FIRST)

QP-CAT depends on the Apache-2.0 shared 3D-viewer library `cluster3d-core`
(`io.github.uw-loci:cluster3d-core:0.1.0`) for its **"3D View"** results tab. Publish that
library to Maven Local before building QP-CAT:

```bash
cd ../cluster3d-core
./gradlew publishToMavenLocal test
cd ../qupath-extension-cell-analysis-tools
./gradlew shadowJar
```

**No `-Dorg.gradle.java.home` pin is needed here any more.** Both repos run Gradle
9.2.1, which works on JDK 25 -- the pin existed only because Gradle 8.12 aborts on Java
25 with a bare `* What went wrong: 25.0.3`. The eleven monorepo repos still on 8.12 do
still need it, which is why `tools/pre-push-checks.sh` keeps hunting for a JDK 17-23:
that hook picks ONE JDK for every repo, so the clamp can only go once they have all
moved.

- The dependency is a **non-transitive `implementation`**:
  `implementation("io.github.uw-loci:cluster3d-core:0.1.0") { isTransitive = false }`. It gets
  SHADED into the `-all.jar` (its own code); QuPath + JavaFX are host-provided, so `isTransitive
  = false` keeps them out of the bundle (core's published POM lists them because qupath-conventions
  injects them). Confirm the shaded classes with:
  `unzip -l build/libs/*-all.jar | grep qupath/ext/qpcat/internal/cluster3d/` -- note the
  RELOCATED package (`build.gradle.kts` rewrites `qupath.ext.cluster3d` into it), so
  grepping the original name finds nothing and looks like a failed shade.
- A user with BOTH QP-CAT and the standalone `qupath-extension-cluster-3d-navigator` installed
  has `cluster3d-core` shaded into both jars at the same pinned version 0.1.0 -> identical
  bytecode, harmless.

### Gradle 9 + shadow 9 (do not "harmonise" the shadow version down)

This repo pins **`com.gradleup.shadow` 9.6.1** while its siblings sit on 8.3.5. That is
deliberate. QP-CAT is the only extension in the monorepo that **relocates** a dependency
(`relocate("qupath.ext.cluster3d", ...)` in `build.gradle.kts`), and shadow 8.3.5's
relocation remapper is Groovy-based: under Gradle 9 it dies inside
`RelocatorRemapper.mapValue` while rewriting invokedynamic call sites, failing
`shadowJar` with `Could not add file ...$QuickDelaunayCustomOptions.class to ZIP`. The
sibling repos never exercise that path, which is how they moved to Gradle 9 on 8.3.5
without noticing.

Gradle 9 also stopped putting the JUnit Platform launcher on the test runtime classpath,
so `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` is required; without
it `test` fails with *"Could not start Gradle Test Executor 1: Failed to load JUnit
Platform"*, which does not name the missing dependency.

Verified on the 8.12 -> 9.2.1 move: 317 tests / 36 classes pass identically on JDK 21 and
JDK 25, and the shaded jar has the same 9,335 entries as the 8.12-built release jar minus
a stray `META-INF/versions/9/module-info.class` that shadow 9 correctly drops.

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

## Python (Appose) script tests

The scripts in `src/main/resources/qupath/ext/qpcat/scripts/` are exec'd by Appose with
injected globals and are **not importable**. `python_tests/` tests them anyway, by
AST-extracting individual top-level helpers from the shipped source
(`conftest.load_script_symbol`). Run with `python3 -m pytest python_tests/ -v`; CI runs
them via `.github/workflows/python-tests.yml`.

**When a script calls into a third-party library, pin the library's contract with a
test.** Issue #10 (Harmony batch correction) was a silent orientation change in
harmonypy's `Z_corr` property between 0.0.x and 0.2.0 -- our pin moved, the call site
did not, and no test of our own code could have caught it. See
`python_tests/test_run_clustering_harmony.py` for the pattern.

## Conventions

- ASCII-only in logs / internal strings (Windows cp1252). Use `qupath.fx.dialogs.Dialogs`.
- No `_legacy` / parallel code paths.

## In-app bug reporter

This extension ships a "Report a Bug..." menu item (`service/BugReportService.java` +
`ui/BugReportDialog.java`). It is one of **three** near-identical copies across the monorepo with
no shared code, so **a change here almost certainly belongs in the other two as well**. Before
touching the scrubber, the payload, or the artifact set, read the contract and the change protocol
in `claude-reports/design/2026-08-19_bug-reporter-architecture.md` (invariants INV-1..INV-9).
User-submitted reports become public GitHub issues -- redaction is a privacy surface.
