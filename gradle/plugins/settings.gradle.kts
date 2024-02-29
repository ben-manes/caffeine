plugins {
  id("com.gradle.enterprise") version "3.16.2"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "1.13"
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

apply(from = "../gradle-enterprise.gradle")
