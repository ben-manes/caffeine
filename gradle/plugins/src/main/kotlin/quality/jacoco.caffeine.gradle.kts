@file:Suppress("PackageDirectoryMismatch")
import org.gradle.util.GradleVersion.version as versionOf
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
  jacoco
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
  if (isEarlyAccess().get()) {
    systemProperty("net.bytebuddy.experimental", true)
    configure<JacocoTaskExtension> {
      isEnabled = false
    }
  }
}

afterEvaluate {
  tasks.withType<JacocoCoverageVerification>().configureEach {
    group = null
  }
  tasks.withType<JacocoReport>().configureEach {
    group = null
  }
}
