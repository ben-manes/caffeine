@file:Suppress("PackageDirectoryMismatch")
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  jacoco
  id("com.github.nbaztec.coveralls-jacoco")
}

dependencies {
  jacocoAgent(libs.jacoco.agent)
  jacocoAnt(libs.jacoco.ant)
}

coverallsJacoco {
  reportPath = layout.buildDirectory.file(
    "reports/jacoco/jacocoFullReport/jacocoFullReport.xml").get().asFile.path
}

val jacocoFullReport = tasks.register<JacocoReport>("jacocoFullReport") {
  group = "Coverage reports"
  description = "Generates an aggregate report"

  subprojects {
    inputs.files(tasks.withType<JavaCompile>().map { it.outputs.files })
      .withPathSensitivity(RELATIVE)
  }
  reports {
    html.required = true // human-readable
    xml.required = true  // required by coveralls
  }
}

tasks.named("coverallsJacoco").configure {
  group = "Coverage reports"
  val isEnabled = isCI()
  onlyIf { isEnabled.get() }
  incompatibleWithConfigurationCache()
  inputs.files(jacocoFullReport.map { it.outputs.files }).withPathSensitivity(RELATIVE)
}

listOf(project(":caffeine"), project(":guava"), project(":jcache")).forEach { coveredProject ->
  coveredProject.plugins.withId("java-library") {
    val extension = coveredProject.the<JavaPluginExtension>()
    coverallsJacoco.reportSourceSets += files(
      extension.sourceSets.named("main").map { it.allSource.srcDirs })
    jacocoFullReport.configure {
      sourceSets(extension.sourceSets["main"])
      mustRunAfter(coveredProject.tasks.withType<Test>())
      executionData(fileTree(rootDir.absolutePath)
        .include("**/*${coveredProject.name}*/**/jacoco/*.exec"))
    }
  }
}
