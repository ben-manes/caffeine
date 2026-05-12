@file:Suppress("PackageDirectoryMismatch")
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  id("org.sonarqube")
  id("coverage.caffeine")
}

sonarqube {
  properties {
    property("sonar.organization", "caffeine")
    property("sonar.host.url", "https://sonarcloud.io")
    property("sonar.cpd.exclusions", "**/simulator/**")
    property("sonar.coverage.exclusions", "**/simulator/**")
    property("sonar.cpd.java.minimumTokens", "1000")
    property("sonar.coverage.jacoco.xmlReportPaths",
      file(layout.buildDirectory.file("reports/jacoco/jacocoFullReport/jacocoFullReport.xml")))
  }
}

val jacocoFullReport by tasks.existing
tasks.named("sonarqube").configure {
  inputs.files(jacocoFullReport.map { it.outputs.files }).withPathSensitivity(RELATIVE)
}
