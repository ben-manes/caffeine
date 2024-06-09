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

idea.project.settings {
  delegateActions.testRunner = PLATFORM
  runConfigurations {
    val jvmArgs = listOf(
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
