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

val mockitoAgent: Configuration by configurations.creating
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
    val jvmArgs = listOf(
      "-javaagent:${mockitoAgent.asPath}",
      "-XX:+EnableDynamicAgentLoading",
      "-XX:SoftRefLRUPolicyMSPerMB=0",
      "-XX:+UseParallelGC",
      "-Xshare:off").joinToString(" ")
    defaults(TestNG::class.java) {
      vmParameters = jvmArgs
    }
    defaults(JUnit::class.java) {
      vmParameters = jvmArgs
    }
    register("Simulator", Application::class.java) {
      mainClass = "com.github.benmanes.caffeine.cache.simulator.Simulator"
      moduleName = "caffeine.simulator.main"
    }
  }
}
