@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED

plugins {
  `java-library`
  id("errorprone.caffeine")
}

val javaTestVersion = javaTestVersion().zip(java.toolchain.languageVersion, ::maxOf)
val mockitoAgent by configurations.registering

dependencies {
  mockitoAgent(libs.mockito) {
    isTransitive = false
  }
}

testing.suites.withType<JvmTestSuite>().configureEach {
  dependencies {
    implementation(platform(libs.asm.bom))
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.junit.jupiter.bom))
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

tasks.check.configure {
  dependsOn(tasks.withType<Test>())
}

tasks.withType<Test>().configureEach {
  inputs.property("javaDistribution", javaDistribution()).optional(true)
  inputs.property("javaVendor", java.toolchain.vendor.map { it.toString() })

  // Use --debug-jvm to remotely attach to the test task
  val defaultJvmArguments = defaultJvmArgs()
  val javaAgent = mockitoAgent.map { it.asPath }
  val javaVersion = javaTestVersion.map { it.asInt() }
  jvmArgumentProviders.add {
    buildList {
      addAll(listOf("-XX:+EnableDynamicAgentLoading", "-javaagent:${javaAgent.get()}",
        "-XX:SoftRefLRUPolicyMSPerMB=0", "-XX:+UnlockDiagnosticVMOptions", "-Xshare:off"))
      if (javaVersion.get() >= 25) {
        add("-XX:+UseCompactObjectHeaders")
      }

      // Randomize instruction scheduling, order of optimizations, inlining decisions,
      // macro node expansion, type checking, and deoptimization traps
      mapOf(
        11 to listOf("-XX:+StressGCM", "-XX:+StressLCM"),
        16 to listOf("-XX:+StressIGVN"),
        17 to listOf("-XX:+StressCCP"),
        22 to listOf("-XX:+StressIncrementalInlining"),
        23 to listOf("-XX:+StressMacroExpansion", "-XX:+StressSecondarySupers"),
        24 to listOf("-XX:+StressUnstableIfTraps"),
      ).filterKeys { javaVersion.get() >= it }.forEach { (_, flags) -> addAll(flags) }

      addAll(defaultJvmArguments.get())
    }
  }

  // Use -Pjfr to generate a profile
  if (providers.gradleProperty("jfr").isPresent) {
    val relativeDir = gradle.startParameter.currentDir
    val jfr = layout.buildDirectory.file("jfr/$name.jfr")
    jvmArgumentProviders.add {
      listOf("-XX:StartFlightRecording=filename=${jfr.absolutePath().get()}")
    }
    doFirst {
      jfr.get().asFile.parentFile.mkdirs()
    }
    doLast {
      println("Java Flight Recording stored at: ${jfr.relativePathFrom(relativeDir).get()}")
    }
  }

  reports {
    junitXml.includeSystemOutLog = isCI().map { !it }
    junitXml.includeSystemErrLog = isCI().map { !it }
    html.required = isCI().map { !it }
  }
  testLogging {
    events(SKIPPED, FAILED)
    exceptionFormat = FULL
    showStackTraces = true
    showExceptions = true
    showCauses = true
  }
  afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
    if (result.resultType == TestResult.ResultType.SKIPPED) {
      throw GradleException("Do not skip tests (e.g. ${descriptor.name})")
    }
  }))
  javaLauncher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    languageVersion = javaTestVersion
    implementation = java.toolchain.implementation
    nativeImageCapable = java.toolchain.nativeImageCapable
  }
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
