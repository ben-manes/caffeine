plugins {
  id("application")
  alias(libs.plugins.graal)
  alias(libs.plugins.versions)
}

dependencies {
  implementation(caffeine())

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

application {
  mainClass = "com.github.benmanes.caffeine.examples.graalnative.Application"
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}

graalvmNative {
  binaries {
    all {
      resources.autodetect()
    }
    named("test") {
      val initializeAtBuildTime = listOf(
        "org.junit.jupiter.api.DisplayNameGenerator\$IndicativeSentences",
        "org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor\$ClassInfo",
        "org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor\$LifecycleMethods",
        "org.junit.jupiter.engine.descriptor.ClassTemplateInvocationTestDescriptor",
        "org.junit.jupiter.engine.descriptor.ClassTemplateTestDescriptor",
        "org.junit.jupiter.engine.descriptor.DynamicDescendantFilter\$Mode",
        "org.junit.jupiter.engine.descriptor.ExclusiveResourceCollector\$1",
        "org.junit.jupiter.engine.descriptor.MethodBasedTestDescriptor\$MethodInfo",
        "org.junit.jupiter.engine.config.InstantiatingConfigurationParameterConverter",
        "org.junit.jupiter.engine.discovery.ClassSelectorResolver\$DummyClassTemplateInvocationContext",
        "org.junit.platform.engine.support.store.NamespacedHierarchicalStore\$EvaluatedValue",
        "org.junit.platform.launcher.core.DiscoveryIssueNotifier",
        "org.junit.platform.launcher.core.HierarchicalOutputDirectoryProvider",
        "org.junit.platform.launcher.core.LauncherConfig",
        "org.junit.platform.launcher.core.LauncherPhase",
        "org.junit.platform.launcher.core.LauncherDiscoveryResult\$EngineResultInfo",
        "org.junit.platform.suite.engine.SuiteTestDescriptor\$LifecycleMethods"
      )
      buildArgs.add("--initialize-at-build-time=${initializeAtBuildTime.joinToString(",")}")
      buildArgs.add("-H:+ReportExceptionStackTraces")
    }
  }
  toolchainDetection = false
}

fun caffeine(): Any {
  if (System.getenv("SNAPSHOT") == "true") {
    return fileTree("../../caffeine/build/libs").also {
      require(!it.files.isEmpty()) { "Caffeine snapshot jar not found" }
    }
  }
  return libs.caffeine
}
