pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}
plugins {
  id("com.gradle.develocity") version "4.0.2"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.3"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

apply(from = "../../gradle/develocity.gradle")

rootProject.name = "graal-native"
