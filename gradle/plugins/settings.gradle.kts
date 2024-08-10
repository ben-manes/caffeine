plugins {
  id("com.gradle.develocity") version "3.17.6"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.0.2"
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      from(files("../libs.versions.toml"))
    }
  }
}

apply(from = "../develocity.gradle")
