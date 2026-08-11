@file:Suppress("PackageDirectoryMismatch")
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.jetbrains.gradle.ext.ActionDelegationConfig.TestRunner.PLATFORM
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.delegateActions
import org.jetbrains.gradle.ext.Application
import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.TestNG
import org.jetbrains.gradle.ext.JUnit

plugins {
  base
  id("org.jetbrains.gradle.plugin.idea-ext")
}

val mockitoAgent = configurations.register("mockitoAgent")

dependencies {
  mockitoAgent(libs.mockito) {
    isTransitive = false
  }
}

idea.project.settings {
  delegateActions {
    delegateBuildRunToGradle = true
    testRunner = PLATFORM
  }
  runConfigurations {
    val params = mockitoAgent.map {
      listOf(
        "-XX:+EnableDynamicAgentLoading",
        "-XX:SoftRefLRUPolicyMSPerMB=0",
        "-javaagent:${it.asPath}",
        "-XX:+UseParallelGC",
        "-Xshare:off"
      ).joinToString(" ")
    }
    defaults(TestNG::class.java) {
      vmParameters = params.get()
    }
    defaults(JUnit::class.java) {
      vmParameters = params.get()
    }
    register("Simulator", Application::class.java) {
      mainClass = "com.github.benmanes.caffeine.cache.simulator.Simulator"
      moduleName = "caffeine.simulator.main"
    }
  }
}

val checkNoGeneratedImports = tasks.register<CheckNoGeneratedImportsTask>("checkNoGeneratedImports") {
  gradleScripts.from(project.fileTree(project.projectDir) {
    include("**/*.gradle.kts")
    exclude("**/build/**")
    exclude("**/bin/**")
  })
  relativeDir = project.rootDir
}

tasks.check.configure {
  dependsOn(checkNoGeneratedImports)
}

/** IntelliJ may unnecessarily auto-import generated script classes. */
abstract class CheckNoGeneratedImportsTask : DefaultTask() {
  @get:Input
  abstract val relativeDir: Property<File>
  @get:InputFiles @get:PathSensitive(RELATIVE)
  abstract val gradleScripts: ConfigurableFileCollection

  init {
    group = "Verification"
    description = "Runs checks for gradle script accessor imports"
  }

  @TaskAction
  fun run() {
    val forbidden = gradleScripts.files.filter { file ->
      file.useLines { lines -> lines.take(10).any { it.contains("gradle.kotlin.dsl.accessors") } }
    }
    check(forbidden.isEmpty()) {
      "Forbidden Gradle accessor imports found:\n" +
        forbidden.joinToString("\n") { "- ${it.relativeTo(relativeDir.get())}" }
    }
  }
}
