/** Guava compatibility adapter. The tests are forked from Guava commit e370dde. */
@file:Suppress("UnstableApiUsage")
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import org.gradle.plugins.ide.eclipse.model.SourceFolder
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath

plugins {
  id("java-library.caffeine")
  id("revapi.caffeine")
}

val caffeineOsgiBundle by configurations.registering {
  configureAsRuntimeIncoming()
}

dependencies {
  api(project(":caffeine"))
  api(libs.guava)

  caffeineOsgiBundle(project(":caffeine", "osgiBundleElements"))
}

tasks.named<JavaCompile>("compileJava").configure {
  options.compilerArgs.add("-Xlint:-exports")
}

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(libs.truth)
      implementation(libs.guava.testlib)
      implementation(libs.spotbugs.annotations)

      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask {
        failOnSkippedTests()
      }
    }
  }
  register("compatibilityTest", JvmTestSuite::class) {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.truth)
      implementation(libs.guava.testlib)
      implementation(libs.spotbugs.annotations)

      runtimeOnly(libs.junit.jupiter.vintage)
    }
    targets.all {
      testTask {
        failOnSkippedTests()
      }
    }
  }
  register("moduleTest", JvmTestSuite::class) {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
    }
    targets.all {
      testTask {
        failOnSkippedTests()
      }
    }
  }
  register<JvmTestSuite>("osgiTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation.bundle(libs.bundles.osgi.test.compile)

      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets.all {
      testTask.configure {
        val caffeineOsgiJarFile = layout.file(caffeineOsgiBundle.map { it.singleFile })
        val guavaJarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
        inputs.files(caffeineOsgiJarFile)
        inputs.files(guavaJarFile)
        failOnSkippedTests()

        val relativeDir = projectDir
        val versions = libs.versions
        doFirst {
          val caffeinePath = caffeineOsgiJarFile.relativePathFrom(relativeDir)
          val guavaPath = guavaJarFile.relativePathFrom(relativeDir)
          systemProperties(mapOf(
            "caffeine.osgi.jar" to caffeinePath.get(),
            "caffeine-guava.osgi.jar" to guavaPath.get(),
            "guava.osgi.version" to versions.guava.get()))
        }
      }
    }
  }
}

tasks.named<Jar>("jar").configure {
  bundle.bnd(mapOf(
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine.guava",
    "Import-Package" to listOf(
      "com.github.benmanes.caffeine.*",
      "com.google.common.*;version=23.2").joinToString(","),
    "Export-Package" to "com.github.benmanes.caffeine.guava"))
}

tasks.withType<CheckForbiddenApis>().configureEach {
  bundledSignatures.addAll(when (name) {
    "forbiddenApisTest" -> listOf("jdk-deprecated", "jdk-internal",
      "jdk-non-portable", "jdk-system-out", "jdk-unsafe")
    else -> listOf("jdk-deprecated", "jdk-internal", "jdk-non-portable",
      "jdk-reflection", "jdk-system-out", "jdk-unsafe")
  })
}

eclipse.classpath.file.whenMerged {
  if (this is EclipseClasspath) {
    entries.removeIf { (it is SourceFolder) && it.path.contains("moduleTest") }
  }
}
