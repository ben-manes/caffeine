@file:Suppress("PackageDirectoryMismatch")
import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent

plugins {
  id("com.github.ben-manes.versions")
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
  checkBuildEnvironmentConstraints = true
  checkConstraints = true
  resolutionStrategy {
    componentSelection {
      all(Action<ComponentSelectionWithCurrent> {
        val ignoredGroups = listOf("com.beust")
        val stable = setOf("com.google.protobuf", "com.hazelcast", "javax.json.bind",
          "org.jetbrains.kotlin", "org.apache.logging.log4j", "org.osgi", "org.slf4j")
        if ((candidate.group in stable) && isNonStable(candidate.version)) {
          reject("Release candidate")
        } else if ((candidate.group in ignoredGroups) && (candidate.version != currentVersion)) {
          reject("Internal dependency")
        }
      })
    }
  }
  outputFormatter {
    outdated.dependencies.removeIf {
      // Ignore Gradle's internal log4j security constraint implicitly added to projects
      it.group == "org.apache.logging.log4j" && (it.version!! < libs.versions.log4j.get())
    }
    PlainTextReporter(project, revision, gradleReleaseChannel).write(System.out, this)
  }
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA", "JRE").any {
    version.uppercase().contains(it)
  }
  val unstableKeyword = listOf("PREVIEW").any {
    version.uppercase().contains(it)
  }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  return (!stableKeyword || unstableKeyword) && !regex.matches(version)
}
