plugins {
  id("com.gradle.develocity") version "3.19"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.0.2"
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

apply(from = "../../gradle/develocity.gradle")

rootProject.name = "coalescing-bulkloader-reactor"
