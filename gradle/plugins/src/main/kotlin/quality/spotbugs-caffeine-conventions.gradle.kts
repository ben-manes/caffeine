import com.github.spotbugs.snom.Confidence.LOW
import com.github.spotbugs.snom.SpotBugsTask
import com.github.spotbugs.snom.Effort.MAX

plugins {
  id("com.github.spotbugs")
}

dependencies {
  spotbugs(libs.spotbugs.core)
  spotbugsPlugins(libs.findsecbugs)
  spotbugsPlugins(libs.spotbugs.contrib)
}

spotbugs {
  effort = MAX
  reportLevel = LOW
  useJavaToolchains = true
  excludeFilter = rootProject.layout.projectDirectory.file("gradle/config/spotbugs/exclude.xml")
}

tasks.withType<SpotBugsTask>().configureEach {
  enabled = System.getProperties().containsKey("spotbugs")
  group = "SpotBugs"
  reports {
    create("html") {
      required = true
    }
    create("sarif") {
      required = true
    }
  }
}
