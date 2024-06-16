import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED

plugins {
  `java-library`
}

dependencies {
  testImplementation(libs.guava)
  testImplementation(libs.guice)
  testImplementation(libs.truth)
  testImplementation(libs.testng)
  testImplementation(libs.mockito)
  testImplementation(libs.hamcrest)
  testImplementation(libs.awaitility)
  testImplementation(libs.bundles.junit)
  testImplementation(libs.bundles.osgi.test.compile)

  testImplementation(platform(libs.asm.bom))
  testImplementation(platform(libs.kotlin.bom))
  testImplementation(platform(libs.junit5.bom))

  testRuntimeOnly(libs.junit5.launcher)
  testRuntimeOnly(libs.bundles.junit.engines)
  testRuntimeOnly(libs.bundles.osgi.test.runtime)
}

tasks.withType<Test>().configureEach {
  jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=0", "-XX:+EnableDynamicAgentLoading", "-Xshare:off")
  if ("debug" in systemProperties) {
    jvmArgs("-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
  }
  if (environment["GRAALVM"] == "true") {
    jvmArgs("-XX:+UnlockExperimentalVMOptions", "-Dgraal.ShowConfiguration=info",
      "-XX:+EnableJVMCI", "-XX:+UseJVMCICompiler", "-XX:+EagerJVMCI")
  }
  if (isCI()) {
    reports.junitXml.includeSystemOutLog = false
    reports.junitXml.includeSystemErrLog = false
  }
  testLogging {
    events = setOf(SKIPPED, FAILED)
    exceptionFormat = FULL
    showStackTraces = true
    showExceptions = true
    showCauses = true
  }
}
