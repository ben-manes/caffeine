plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.6.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

rootProject.name = "hibernate"
