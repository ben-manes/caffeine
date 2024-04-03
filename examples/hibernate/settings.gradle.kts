plugins {
  id("com.gradle.enterprise") version "3.17"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "1.13"
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

apply(from = "../../gradle/gradle-enterprise.gradle")

rootProject.name = "hibernate"
