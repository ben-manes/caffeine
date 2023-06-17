import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
  id("net.ltgt.nullaway")
  id("net.ltgt.errorprone")
}

dependencies {
  errorprone(libs.errorprone.core) {
    exclude(group = "com.github.ben-manes.caffeine")
  }
  errorprone(layout.buildDirectory.files("errorprone/caffeine-${libs.versions.caffeine.get()}.jar"))

  errorprone(libs.nullaway.core)
  errorprone(libs.errorprone.mockito)
  errorprone(libs.errorprone.support)
}

tasks.withType<JavaCompile>().configureEach {
  dependsOn(downloadCaffeine)

  options.forkOptions.jvmArgs!!.addAll(DisableStrongEncapsulationJvmArgs)
  options.errorprone {
    if (System.getenv("JDK_EA") == "true") {
      isEnabled = false
    }

    disabledChecks.forEach { disable(it) }
    enabledChecks.forEach { enable(it) }
    errorChecks.forEach { error(it) }

    nullaway {
      if (name.contains("Test") || name.contains("Jmh")) {
        disable()
      }
      annotatedPackages.add("com.github.benmanes.caffeine")
      annotatedPackages.add("com.google.common")
      checkOptionalEmptiness = true
      suggestSuppressions = true
      checkContracts = true
    }
  }
}

// Gradle rewrites ErrorProne's dependency on Caffeine to a project dependency, which then fails.
// Instead we have to download and trick the build to put the jar on the compiler's classpath.
val downloadCaffeine by tasks.registering {
  val version = libs.versions.caffeine.get()
  inputs.property("version", version)

  val library = file(layout.buildDirectory.file("errorprone/caffeine-$version.jar"))
  outputs.cacheIf { true }
  outputs.file(library)

  doFirst {
    library.parentFile.mkdirs()
    val url = URL("https://repo1.maven.org/maven2/"
      + "com/github/ben-manes/caffeine/caffeine/$version/caffeine-$version.jar")
    url.openStream().buffered().use {
      Files.copy(it, library.toPath(), REPLACE_EXISTING)
    }
  }
}

val errorChecks = listOf(
  "NullAway",
)
val disabledChecks = listOf(
  "AvoidObjectArrays",
  "IsInstanceLambdaUsage",
  "LexicographicalAnnotationListing",
  "MissingSummary",
  "StaticImport",
)
val enabledChecks = listOf(
  "AssertFalse",
  "BuilderReturnThis",
  "CatchingUnchecked",
  "CanIgnoreReturnValueSuggester",
  "CheckedExceptionNotThrown",
  "ClassName",
  "ComparisonContractViolated",
  "CannotMockFinalClass",
  "CannotMockFinalMethod",
  "DepAnn",
  "EmptyIf",
  "EqualsBrokenForNull",
  "FieldCanBeLocal",
  "FieldCanBeStatic",
  "ForEachIterable",
  "FuzzyEqualsShouldNotBeUsedInEqualsMethod",
  "FunctionalInterfaceClash",
  "IterablePathParameter",
  "LongLiteralLowerCaseSuffix",
  "MissingBraces",
  "MissingDefault",
  "MixedArrayDimensions",
  "MissingDefault",
  "NoAllocation",
  "PackageLocation",
  "PreferredInterfaceType",
  "PreferJavaTimeOverload",
  "RedundantThrows",
  "RemoveUnusedImports",
  "ReturnsNullCollection",
  "SelfAlwaysReturnsThis",
  "StronglyTypeByteString",
  "StronglyTypeTime",
  "SwitchDefault",
  "TimeUnitMismatch",
  "TransientMisuse",
  "UnnecessarilyVisible",
  "UnnecessaryAnonymousClass",
  "UnnecessaryOptionalGet",
  "UnnecessarilyUsedValue",
  "UnsafeLocaleUsage",
  "UnusedTypeParameter",
  "UsingJsr305CheckReturnValue",
  "YodaCondition",
)
