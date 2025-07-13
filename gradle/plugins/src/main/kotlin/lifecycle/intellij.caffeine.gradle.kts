import org.jetbrains.gradle.ext.ActionDelegationConfig.TestRunner.PLATFORM
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.delegateActions
import org.jetbrains.gradle.ext.Application
import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.TestNG
import org.jetbrains.gradle.ext.JUnit

plugins {
  id("org.jetbrains.gradle.plugin.idea-ext")
}

val mockitoAgent by configurations.registering
val excludes = rootDir.walkTopDown().filter {
  it.name in listOf("bin", "build", "test-output", ".classpath",
    ".gradle", ".kotlin", ".project", ".settings")
}.toSet()

dependencies {
  mockitoAgent(libs.mockito) {
    isTransitive = false
  }
}

allprojects {
  apply(plugin = "idea")

  idea.module {
    excludeDirs = excludes
    isDownloadSources = true
    isDownloadJavadoc = true
  }
}

idea.project.settings {
  delegateActions {
    delegateBuildRunToGradle = false
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
