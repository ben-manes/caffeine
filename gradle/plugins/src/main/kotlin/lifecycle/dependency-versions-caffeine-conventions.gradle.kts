import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  id("com.github.ben-manes.versions")
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
  checkBuildEnvironmentConstraints = true
  checkConstraints = true
  resolutionStrategy {
    componentSelection {
      all {
        val ignoredGroups = listOf("com.beust", "org.apache.logging.log4j")
        val stable = setOf("com.hazelcast", "javax.json.bind",
          "org.jetbrains.kotlin", "org.osgi", "org.slf4j")
        if ((candidate.group in stable) && isNonStable(candidate.version)) {
          reject("Release candidate")
        } else if ((candidate.module == "commons-io") && candidate.version.startsWith("2003")) {
          reject("Bad release")
        } else if ((candidate.group in ignoredGroups) && (candidate.version != currentVersion)) {
          reject("Internal dependency")
        }
      }
    }
    force(libs.guice)
    force(libs.hazelcast)
    force(libs.commons.collections4)
    force(libs.bundles.coherence.get())
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
