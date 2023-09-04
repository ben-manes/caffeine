import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  id("com.github.ben-manes.versions")
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
  resolutionStrategy {
    componentSelection {
      all {
        val stable = listOf("javax.json.bind", "org.jetbrains.kotlin", "org.osgi")
        if ((candidate.group in stable) && isNonStable(candidate.version)) {
          reject("Release candidate")
        } else if ((candidate.module == "commons-io") && candidate.version.startsWith("2003")) {
          reject("Bad release")
        }
      }
    }
    force(libs.guice)
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
