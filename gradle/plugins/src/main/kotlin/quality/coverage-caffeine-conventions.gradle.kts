import org.gradle.api.plugins.JavaPluginExtension

plugins {
  jacoco
  id("com.github.kt3k.coveralls")
  id("jacoco-caffeine-conventions")
}

dependencies {
  jacocoAgent(libs.jacoco.agent)
  jacocoAnt(libs.jacoco.ant)
}

coveralls {
  jacocoReportPath = layout.buildDirectory.file(
    "reports/jacoco/jacocoFullReport/jacocoFullReport.xml")
}

val testReport = tasks.register<TestReport>("testReport") {
  group = "Build"
  description = "Generates an aggregate test report"
  destinationDirectory.set(layout.buildDirectory.dir("reports/allTests"))
}

val jacocoFullReport by tasks.registering(JacocoReport::class) {
  group = "Coverage reports"
  description = "Generates an aggregate report"

  subprojects {
    dependsOn(tasks.compileTestJava)
  }
  reports {
    html.required.set(true) // human readable
    xml.required.set(true)  // required by coveralls
  }
}

tasks.named("coveralls").configure {
  group = "Coverage reports"
  description = "Uploads the aggregated coverage report to Coveralls"
  dependsOn(jacocoFullReport)
  onlyIf { System.getenv().contains("CI") }
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}

subprojects {
  tasks.withType<Test>().configureEach {
    testReport.configure {
      testResults.from(this@configureEach.binaryResultsDirectory)
    }
    dependsOn(tasks.jar)

    // ensure tasks don't overwrite the default report directories used by the 'test' task
    reports.html.outputLocation.set(file(layout.buildDirectory.file("reports/${name}")))
    reports.junitXml.outputLocation.set(file(layout.buildDirectory.file("reports/${name}/results")))
    binaryResultsDirectory.set(layout.buildDirectory.dir("reports/${name}/results/binary/${name}"))
  }
}

listOf(project(":caffeine"), project(":guava"), project(":jcache")).forEach { coveredProject ->
  coveredProject.plugins.withId("java-library") {
    val extension = coveredProject.the<JavaPluginExtension>()
    coveralls.sourceDirs.addAll(
      extension.sourceSets["main"].allSource.srcDirs.map { file -> file.path })
    jacocoFullReport.configure {
      sourceSets(extension.sourceSets["main"])
      mustRunAfter(coveredProject.tasks.withType<Test>())
      executionData(fileTree(rootDir.absolutePath)
        .include("**/*${coveredProject.name}*/**/jacoco/*.exec"))
    }
  }
}
