@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
import com.github.spotbugs.snom.Confidence.LOW
import com.github.spotbugs.snom.SpotBugsTask
import com.github.spotbugs.snom.Effort.MAX

plugins {
  `java-library`
  id("com.github.spotbugs")
}

configurations.spotbugsSlf4j {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j") {
      useVersion(libs.versions.slf4j.asProvider().get())
    }
  }
}

dependencies {
  spotbugs(libs.spotbugs)
  spotbugsPlugins(libs.findsecbugs)
  spotbugsPlugins(libs.spotbugs.contrib)
}

spotbugs {
  effort = MAX
  reportLevel = LOW
  useJavaToolchains = true
  toolVersion = libs.versions.spotbugs.asProvider()
  excludeFilter = rootProject.layout.projectDirectory.file("gradle/config/spotbugs/exclude.xml")
}

tasks.register("spotbugs") {
  group = "SpotBugs"
  description = "Run all SpotBugs checks."
  dependsOn(tasks.withType<SpotBugsTask>())
}

tasks.withType<SpotBugsTask>().configureEach {
  val isEnabled = providers.gradleProperty("spotbugs")
  onlyIf { isEnabled.isPresent }
  group = "SpotBugs"
  reports.create("html") {
    required = true
  }
  launcher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    languageVersion = javaRuntimeVersion()
    implementation = java.toolchain.implementation
    nativeImageCapable = java.toolchain.nativeImageCapable
  }

  val runtimeVersion = javaRuntimeVersion().map { it.asInt() }
  val javaVersion = java.toolchain.languageVersion.map { it.asInt() }
  doFirst {
    require(javaVersion.get() >= runtimeVersion.get()) {
      "Requires Java ${runtimeVersion.get()} or higher (use -PjavaVersion=${runtimeVersion.get()})."
    }
  }
}
