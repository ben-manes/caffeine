plugins {
  id("org.sonarqube")
  id("coverage-caffeine-conventions")
}

sonarqube {
  properties {
    property("sonar.organization", "caffeine")
    property("sonar.host.url", "https://sonarcloud.io")
    property("sonar.cpd.exclusions", "**/simulator/**")
    property("sonar.token", System.getenv("SONAR_TOKEN"))
    property("sonar.coverage.exclusions", "**/simulator/**")
    property("sonar.coverage.jacoco.xmlReportPaths",
      file(layout.buildDirectory.file("reports/jacoco/jacocoFullReport/jacocoFullReport.xml")))
  }
}

val jacocoFullReport by tasks.existing
tasks.named("sonarqube").configure {
  dependsOn(jacocoFullReport)
}
