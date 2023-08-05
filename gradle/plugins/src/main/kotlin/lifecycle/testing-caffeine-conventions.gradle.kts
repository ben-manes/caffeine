import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED

plugins {
  `java-library`
}

configurations.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("org.hamcrest:hamcrest-core")).using(module(libs.hamcrest.get().toString()))
  }
}

dependencies {
  testImplementation(libs.guava)
  testImplementation(libs.guice)
  testImplementation(libs.testng)
  testImplementation(libs.mockito)
  testImplementation(libs.hamcrest)
  testImplementation(libs.awaitility)
  testImplementation(libs.bundles.junit)
  testImplementation(libs.bundles.truth)
  testImplementation(libs.bundles.osgi.test.compile)

  testImplementation(platform(libs.asm.bom))
  testImplementation(platform(libs.kotlin.bom))
  testImplementation(platform(libs.junit5.bom))

  testRuntimeOnly(libs.junit5.launcher)
  testRuntimeOnly(libs.bundles.junit.engines)
  testRuntimeOnly(libs.bundles.osgi.test.runtime)
}

tasks.withType<Test>().configureEach {
  jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=0")
  if ("debug" in systemProperties) {
    jvmArgs("-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
  }
  if (environment["GRAALVM"] == "true") {
    jvmArgs("-XX:+UnlockExperimentalVMOptions", "-Dgraal.ShowConfiguration=info",
      "-XX:+EnableJVMCI", "-XX:+UseJVMCICompiler", "-XX:+EagerJVMCI")
  }
  testLogging {
    events = setOf(SKIPPED, FAILED)
    exceptionFormat = FULL
    showStackTraces = true
    showExceptions = true
    showCauses = true
  }
}
