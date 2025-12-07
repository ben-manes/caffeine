plugins {
  id("com.gradle.develocity") version "4.3"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.4.0"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
  versionCatalogs {
    register("libs") {
      from(files("../libs.versions.toml"))
    }
  }
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
apply(from = "../develocity.gradle")
