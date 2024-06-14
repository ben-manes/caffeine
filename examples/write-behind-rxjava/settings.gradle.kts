plugins {
  id("com.gradle.develocity") version "3.17.5"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.0.1"
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

apply(from = "../../gradle/develocity.gradle")

rootProject.name = "writebehind-rxjava"
