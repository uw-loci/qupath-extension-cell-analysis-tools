pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

qupath {
    version = "0.7.0"
}

plugins {
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}
