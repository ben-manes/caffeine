@file:Suppress("PackageDirectoryMismatch")
import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
  val log4jVersion = libs.versions.log4j.get()
  val projectPath = project.path

  filterConfigurations = Spec<Configuration> {
    it.attributes.keySet().none { attr -> attr.name == "dagp.internal.artifacts" }
  }
  checkBuildEnvironmentConstraints = true
  checkConstraints = true
  resolutionStrategy {
    componentSelection {
      all(Action {
        val ignoredGroups = listOf("com.beust")
        val kotlinGroups = listOf("org.jetbrains", "org.jetbrains.kotlin")
        val stable = setOf("com.google.protobuf", "com.hazelcast", "javax.json.bind",
          "org.jetbrains.kotlin", "org.apache.logging.log4j", "org.osgi", "org.slf4j")
        if ((candidate.group in stable) && isNonStable(candidate.version)) {
          reject("Release candidate")
        } else if ((candidate.group in ignoredGroups) && (candidate.version != currentVersion)) {
          reject("Internal dependency")
        } else if ((candidate.group in kotlinGroups) && (candidate.module != "kotlin-bom")
            && (candidate.version != currentVersion)) {
          reject("Use kotlin bill of materials")
        }
      })
    }
  }
  outputFormatter {
    outdated.dependencies.removeIf {
      // Ignore Gradle's internal log4j security constraint implicitly added to projects
      it.group == "org.apache.logging.log4j" && (it.version!! < log4jVersion)
    }
    PlainTextReporter(projectPath, revision, gradleReleaseChannel).write(System.out, this)
  }
}

private fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA", "JRE").any {
    version.uppercase().contains(it)
  }
  val unstableKeyword = listOf("PREVIEW").any {
    version.uppercase().contains(it)
  }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  return (!stableKeyword || unstableKeyword) && !regex.matches(version)
}
