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
    property("sonar.coverage.jacoco.xmlReportPaths",
      file(layout.buildDirectory.file("reports/jacoco/jacocoFullReport/jacocoFullReport.xml")))
  }
}

val jacocoFullReport = tasks.named("jacocoFullReport")
tasks.named("sonarqube").configure {
  inputs.files(jacocoFullReport.map { it.outputs.files }).withPathSensitivity(RELATIVE)
}
