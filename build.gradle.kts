plugins {
    groovy
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
    id("com.github.spotbugs") version "6.5.0"
}

qupathExtension {
    name = "qupath-extension-cell-analysis-tools"
    group = "io.github.uw-loci"
    version = "0.3.4"
    description = "QP-CAT: Cell Analysis Tools for QuPath. Python-powered clustering, phenotyping, classification, and spatial analysis for multiplexed imaging data."
    automaticModule = "io.github.uw-loci.extension.qpcat"
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

val javafxVersion = "17.0.2"

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.gson)

    // Appose for embedded Java-Python IPC with shared memory
    implementation("org.apposed:appose:0.11.0")

    // SnakeYAML for the v1 YAML headless-batch parser. Already present on
    // QuPath's runtime classpath transitively (via commonmark-ext-yaml-front-matter)
    // but not on compileClasspath; declare explicitly so the batch parser
    // compiles against the same version that ships at runtime.
    compileOnly("org.yaml:snakeyaml:2.3")
    testImplementation("org.yaml:snakeyaml:2.3")

    shadow(libs.bundles.groovy)

    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.7.0-rc1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.2.0")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType<JavaCompile> {
    options.release.set(21) // QuPath 0.7 runs on Java 21; pin bytecode target so any build JDK emits loadable classes
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf(
        "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
        "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
    )
}

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection (gates the build)
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}
// QuPath 0.7.0's maven artifacts are published as requiring JVM 25 (org.gradle.jvm.version=25),
// even though the QuPath app runs on Java 21. options.release=21 makes Gradle resolve a
// JVM-21-compatible classpath, which then rejects those JVM-25 artifacts on a clean build. Force
// the resolvable classpaths to request JVM 25 so the deps resolve; bytecode target (21) is
// unaffected, so the jar still loads on Java 21. (Upstream QuPath metadata bug; remove if fixed.)
configurations.configureEach {
    if (isCanBeResolved) {
        attributes {
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}
