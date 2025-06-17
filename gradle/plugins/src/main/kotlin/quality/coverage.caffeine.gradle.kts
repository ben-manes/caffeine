import org.gradle.api.plugins.JavaPluginExtension

plugins {
  id("jacoco.caffeine")
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

val testReport = tasks.register<TestReport>("testReport") {
  group = "Build"
  description = "Generates an aggregate test report"
  destinationDirectory = layout.buildDirectory.dir("reports/allTests")
}

val jacocoFullReport by tasks.registering(JacocoReport::class) {
  group = "Coverage reports"
  description = "Generates an aggregate report"

  subprojects {
    inputs.files(tasks.named<JavaCompile>("compileTestJava").map { it.outputs.files })
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
  inputs.files(jacocoFullReport.map { it.outputs.files })
}

subprojects {
  tasks.withType<Test>().configureEach {
    testReport.configure {
      testResults.from(this@configureEach.binaryResultsDirectory)
    }
    inputs.files(tasks.named<Jar>("jar").map { it.outputs.files })

    // ensure tasks don't overwrite the default report directories used by the 'test' task
    reports.html.outputLocation = file(layout.buildDirectory.file("reports/$name"))
    reports.junitXml.outputLocation = file(layout.buildDirectory.file("reports/$name/results"))
    binaryResultsDirectory = layout.buildDirectory.dir("reports/$name/results/binary/$name")
  }
}

listOf(project(":caffeine"), project(":guava"), project(":jcache")).forEach { coveredProject ->
  coveredProject.plugins.withId("java-library") {
    val extension = coveredProject.the<JavaPluginExtension>()
    coverallsJacoco.reportSourceSets += extension.sourceSets["main"].allSource.srcDirs
    jacocoFullReport.configure {
      sourceSets(extension.sourceSets["main"])
      mustRunAfter(coveredProject.tasks.withType<Test>())
      executionData(fileTree(rootDir.absolutePath)
        .include("**/*${coveredProject.name}*/**/jacoco/*.exec"))
    }
  }
}
