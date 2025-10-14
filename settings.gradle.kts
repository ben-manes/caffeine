pluginManagement {
  includeBuild("gradle/plugins")
}
plugins {
  id("com.gradle.develocity") version "4.2.2"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.4.0"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
apply(from = "$rootDir/gradle/develocity.gradle")

rootProject.name = "caffeine"
include("caffeine", "guava", "jcache", "simulator")
