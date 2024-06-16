import org.gradle.util.GradleVersion.version as versionOf
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
  jacoco
  `java-library`
}

jacoco {
  toolVersion = maxOf(versionOf(toolVersion), versionOf(libs.versions.jacoco.get())).version
}

tasks.withType<JacocoReport>().configureEach {
  group = "Coverage reports"
  description = "Generates a test coverage report for a project"

  reports {
    xml.required = true
    html.required = true
  }
}

tasks.withType<Test>().configureEach {
  if (environment["JDK_EA"] == "true") {
    systemProperty("net.bytebuddy.experimental", true)
    configure<JacocoTaskExtension> {
      enabled = false
    }
  }
}
