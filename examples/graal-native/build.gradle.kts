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
      buildArgs.add("--initialize-at-build-time=org.junit.jupiter.engine.config.InstantiatingConfigurationParameterConverter")
      buildArgs.add("--initialize-at-build-time=org.junit.platform.launcher.core.LauncherConfig")
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
