@file:Suppress("PackageDirectoryMismatch")
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

plugins {
  `java-library`
  id("net.ltgt.nullaway")
  id("net.ltgt.errorprone")
}

sourceSets.configureEach {
  configurations.named(annotationProcessorConfigurationName) {
    exclude(group = libs.caffeine.get().group)
    dependencies.add(project.dependencies.create(
      layout.buildDirectory.files("errorprone/caffeine-${libs.versions.caffeine.get()}.jar")))
  }
}

dependencies {
  errorprone(libs.nullaway)
  errorprone(libs.errorprone)
  errorprone(libs.errorprone.mockito)
  errorprone(libs.bundles.errorprone.support)
}

// Gradle rewrites ErrorProne's dependency on Caffeine to a project dependency, which then fails.
// Instead, we have to download and trick the build to put the jar on the compiler's classpath.
val downloadCaffeine by tasks.registering {
  val version = libs.versions.caffeine.get()
  inputs.property("version", version)

  val library = file(layout.buildDirectory.file("errorprone/caffeine-$version.jar"))
  outputs.cacheIf { true }
  outputs.file(library)

  doFirst {
    library.parentFile.mkdirs()
    val uri = URI.create("https://repo1.maven.org/maven2/"
      + "com/github/ben-manes/caffeine/caffeine/$version/caffeine-$version.jar")
    uri.toURL().openStream().buffered().use {
      Files.copy(it, library.toPath(), REPLACE_EXISTING)
    }
  }
}

tasks.withType<JavaCompile>().configureEach {
  inputs.files(downloadCaffeine.map { it.outputs.files })

  options.apply {
    errorprone {
      isEnabled = isEarlyAccess().map { !it }
      allDisabledChecksAsWarnings = true
      allSuggestionsAsWarnings = true

      errorproneArgs.add(buildString {
        append("-XepOpt:Refaster:NamePattern=^")
        disabledRules().forEach { rule ->
          append("(?!")
          append(rule)
          append(".*)")
        }
        append(".*")
      })
      disabledChecks().forEach { disable(it) }

      nullaway {
        if (java.toolchain.languageVersion.get().canCompileOrRun(17)) {
          annotatedPackages.add("org.junit.jupiter")
        }
        annotatedPackages.add("com.github.benmanes.caffeine")
        annotatedPackages.add("com.google.common")
        annotatedPackages.add("com.google.inject")
        handleTestAssertionLibraries = true
        checkOptionalEmptiness = true
        suggestSuppressions = true
        checkContracts = true
        isJSpecifyMode = true
        error()
      }
    }
  }
}

private fun disabledChecks() = listOf(
  "AddNullMarkedToClass",
  "AssignmentExpression",
  "AvoidObjectArrays",
  "CannotMockMethod",
  "ConstantNaming",
  "IsInstanceLambdaUsage",
  "Java8ApiChecker",
  "LexicographicalAnnotationListing",
  "MissingSummary",
  "MultipleTopLevelClasses",
  "PatternMatchingInstanceof",
  "RedundantNullCheck",
  "Slf4jLoggerDeclaration",
  "StatementSwitchToExpressionSwitch",
  "StaticImport",
  "SuppressWarningsWithoutExplanation",
  "UngroupedOverloads",
)
@Suppress("CanConvertToMultiDollarString")
private fun disabledRules() = listOf(
  "ImmutableListRules\\\$ImmutableListBuilder",
  "ImmutableListRules\\\$ImmutableListOf\\d*",
  "ImmutableMapRules\\\$ImmutableMapBuilder",
  "ImmutableMapRules\\\$ImmutableMapOf\\d*",
  "ImmutableSetMultimapRules\\\$ImmutableSetMultimapBuilder",
  "ImmutableSetRules\\\$ImmutableSetOf\\d*",
  "ImmutableTableRules\\\$ImmutableTableBuilder",
  "JUnitToAssertJRules",
  "MapRules",
  "NullRules\\\$RequireNonNullElse",
  "PreconditionsRules",
  "TestNGToAssertJRules"
)
