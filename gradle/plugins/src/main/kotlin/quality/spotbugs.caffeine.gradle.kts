import com.github.spotbugs.snom.Confidence.LOW
import com.github.spotbugs.snom.SpotBugsTask
import com.github.spotbugs.snom.Effort.MAX

plugins {
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

tasks.withType<SpotBugsTask>().configureEach {
  val isEnabled = providers.systemProperty("spotbugs")
  onlyIf { isEnabled.isPresent }
  group = "SpotBugs"
  reports {
    create("html") {
      required = true
    }
  }
}
