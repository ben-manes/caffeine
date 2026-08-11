@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  jacoco
  `jacoco-report-aggregation`
  id("com.github.nbaztec.coveralls-jacoco")
}

val coveredProjects = listOf(":caffeine", ":guava", ":jcache")
val coverageData = configurations.resolvable("coverageData") {
  extendsFrom(configurations.getByName("jacocoAggregation"))
  selectsCoverageData()
  isTransitive = false
}

configurations.named("aggregateCodeCoverageReportResults").configure { selectsRuntimeJar() }

dependencies {
  jacocoAgent(libs.jacoco.agent)
  jacocoAnt(libs.jacoco.ant)
  coveredProjects.forEach { jacocoAggregation(project(it)) }
}

reporting.reports.register<JacocoCoverageReport>("jacocoFullReport") {
  testSuiteName = "test"
}

val jacocoFullReport = tasks.named<JacocoReport>("jacocoFullReport")
jacocoFullReport.configure {
  group = "Coverage reports"
  description = "Generates an aggregate report"

  mustRunAfter(coverageData)
  executionData.setFrom(fileTree(rootDir)
    .include(coveredProjects.map { "**/*${it.removePrefix(":")}*/**/jacoco/*.exec" }))
  reports {
    html.required = true // human-readable
    xml.required = true  // required by coveralls
  }
}

coverallsJacoco {
  reportPath = layout.buildDirectory.file(
    "reports/jacoco/jacocoFullReport/jacocoFullReport.xml").get().asFile.path
  reportSourceSets = files(jacocoFullReport.map { it.sourceDirectories })
}

tasks.named("coverallsJacoco").configure {
  group = "Coverage reports"
  val isEnabled = isCI()
  onlyIf { isEnabled.get() }
  incompatibleWithConfigurationCache()
  inputs.files(jacocoFullReport.map { it.outputs.files }).withPathSensitivity(RELATIVE)
}
