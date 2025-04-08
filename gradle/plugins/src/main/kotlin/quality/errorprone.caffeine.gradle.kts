import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
  id("net.ltgt.nullaway")
  id("net.ltgt.errorprone")
}

dependencies {
  errorprone(libs.errorprone) {
    exclude(group = libs.caffeine.get().group)
  }
  errorprone(layout.buildDirectory.files("errorprone/caffeine-${libs.versions.caffeine.get()}.jar"))

  errorprone(libs.nullaway)
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
  dependsOn(downloadCaffeine)

  options.apply {
    forkOptions.jvmArgs!!.addAll(DisableStrongEncapsulationJvmArgs)
    errorprone {
      isEnabled = isEarlyAccess().map { !it }
      allDisabledChecksAsWarnings = true

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
        annotatedPackages.add("com.github.benmanes.caffeine")
        annotatedPackages.add("com.google.common")
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

fun disabledChecks() = listOf(
  "AndroidJdkLibsChecker",
  "AssignmentExpression",
  "AvoidObjectArrays",
  "ConstantNaming",
  "IsInstanceLambdaUsage",
  "Java7ApiChecker",
  "Java8ApiChecker",
  "LexicographicalAnnotationListing",
  "MissingSummary",
  "MultipleTopLevelClasses",
  "PatternMatchingInstanceof",
  "Slf4jLoggerDeclaration",
  "StaticImport",
  "SuppressWarningsWithoutExplanation",
  "UngroupedOverloads",

  // https://github.com/google/error-prone/issues/4914
  "UnescapedEntity",
)
fun disabledRules() = listOf(
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
