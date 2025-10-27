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
  javaTestVersion().map(JavaLanguageVersion::of).orElse(it)
}
val mockitoAgent by configurations.registering

dependencies {
  testImplementation(libs.truth)
  testImplementation(libs.testng)
  testImplementation(libs.bundles.junit)
  testImplementation(platform(libs.asm.bom))
  testImplementation(platform(libs.kotlin.bom))
  testImplementation(platform(libs.junit.jupiter.bom))

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
  inputs.property("javaDistribution", javaDistribution()).optional(true)
  inputs.property("javaVendor", java.toolchain.vendor.map { it.toString() })

  // Use --debug-jvm to remotely attach to the test task
  val defaultJvmArguments = defaultJvmArgs()
  val javaAgent = mockitoAgent.map { it.asPath }
  jvmArgumentProviders.add { defaultJvmArguments.get() }
  jvmArgumentProviders.add { listOf("-javaagent:${javaAgent.get()}") }
  jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=0", "-XX:+EnableDynamicAgentLoading", "-Xshare:off")

  // Use -Pjfr to generate a profile
  if (rootProject.hasProperty("jfr")) {
    val jfr = layout.buildDirectory.file("jfr/$name.jfr").get().asFile
    jvmArgs("-XX:StartFlightRecording=filename=${jfr.absolutePath}")
    val relativeDir = gradle.startParameter.currentDir
    doFirst { jfr.parentFile.mkdirs() }
    doLast { println("Java Flight Recording stored at: ${jfr.relativeTo(relativeDir)}") }
  }

  reports {
    junitXml.includeSystemOutLog = isCI().map { !it }
    junitXml.includeSystemErrLog = isCI().map { !it }
    html.required = isCI().map { !it }
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
      vendor = java.toolchain.vendor
      languageVersion = javaTestVersion
      implementation = java.toolchain.implementation
      nativeImageCapable = java.toolchain.nativeImageCapable
    }
  )
}

tasks.withType<JavaCompile>().configureEach {
  if (name.endsWith("TestJava")) {
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
}
