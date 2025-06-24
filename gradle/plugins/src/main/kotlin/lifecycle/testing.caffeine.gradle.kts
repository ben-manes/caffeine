import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
  `java-library`
  id("errorprone.caffeine")
}

val javaTestVersion: Provider<JavaLanguageVersion> = java.toolchain.languageVersion.flatMap {
  providers.environmentVariable("JAVA_TEST_VERSION").map(JavaLanguageVersion::of).orElse(it)
}
val mockitoAgent: Configuration by configurations.creating

dependencies {
  testImplementation(libs.truth)
  testImplementation(libs.testng)
  testImplementation(libs.bundles.junit)
  testImplementation(platform(libs.asm.bom))
  testImplementation(platform(libs.kotlin.bom))
  testImplementation(platform(libs.junit.jupiter.bom))

  testRuntimeOnly(libs.junit.jupiter.launcher)

  mockitoAgent(libs.mockito) {
    isTransitive = false
  }
}

configurations.all {
  val junitJupiterGroups = listOf("org.junit", "org.junit.jupiter", "org.junit.vintage")
  resolutionStrategy.eachDependency {
    if ((requested.group in junitJupiterGroups)
        && (java.toolchain.languageVersion.get().asInt() < 17)) {
      useVersion("5.13.2")
    }
  }
}

tasks.withType<Test>().configureEach {
  inputs.property("javaDistribution",
    providers.environmentVariable("JDK_DISTRIBUTION")).optional(true)
  inputs.property("javaVendor", java.toolchain.vendor.map { it.toString() })

  // Use --debug-jvm to remotely attach to the test task
  jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=0", "-XX:+EnableDynamicAgentLoading", "-Xshare:off")
  jvmArgs("-javaagent:${mockitoAgent.asPath}")
  jvmArgs(defaultJvmArgs())
  reports {
    junitXml.includeSystemOutLog = isCI().map { !it }
    junitXml.includeSystemErrLog = isCI().map { !it }
  }
  testLogging {
    events = setOf(SKIPPED, FAILED)
    exceptionFormat = FULL
    showStackTraces = true
    showExceptions = true
    showCauses = true
  }
  javaLauncher.set(
    javaToolchains.launcherFor {
      languageVersion.set(javaTestVersion)
    }
  )
}

tasks.named<JavaCompile>("compileTestJava").configure {
  options.errorprone.nullaway {
    customInitializerAnnotations.addAll(listOf(
      "org.testng.annotations.BeforeClass",
      "org.testng.annotations.BeforeMethod"))
    externalInitAnnotations.addAll(listOf(
      "org.mockito.testng.MockitoSettings",
      "picocli.CommandLine.Command"))
    excludedFieldAnnotations.addAll(listOf(
      "org.junit.jupiter.params.Parameter",
      "jakarta.inject.Inject",
      "org.mockito.Captor",
      "org.mockito.Mock"))
  }
}
