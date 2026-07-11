@file:Suppress("UnstableApiUsage")
pluginManagement {
  includeBuild("gradle/plugins")
  repositories {
    if (providers.environmentVariable("CI").isPresent) {
      maven {
        name = "googleMavenCentralMirror"
        url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenContent { releasesOnly() }
      }
    }
    gradlePluginPortal()
  }
}
plugins {
  id("com.gradle.develocity") version "4.5.0"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.7.0"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    if (providers.environmentVariable("CI").isPresent) {
      maven {
        name = "googleMavenCentralMirror"
        url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenContent { releasesOnly() }
      }
    }
    mavenCentral()
  }
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
apply(from = "$rootDir/gradle/develocity.gradle")

rootProject.name = "caffeine"
include("caffeine", "guava", "jcache", "simulator")
