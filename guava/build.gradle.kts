/** Guava compatibility adapter. The tests are forked from Guava commit e370dde. */
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  id("java-library.caffeine")
  id("revapi.caffeine")
}

dependencies {
  api(project(":caffeine"))
  api(libs.guava)
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
      implementation.bundle(libs.bundles.slf4j.nop)
      implementation.bundle(libs.bundles.osgi.test.compile)

      runtimeOnly(libs.junit.jupiter.testng)
      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets.named("test") {
      testTask.configure {
        project(":caffeine").plugins.withId("java-library") {
          val caffeineJar = project(":caffeine").tasks.named<Jar>("jar")
          val guavaJar = project(":guava").tasks.named<Jar>("jar")
          inputs.files(caffeineJar.map { it.outputs.files })
          inputs.files(guavaJar.map { it.outputs.files })

          val caffeineJarFile = caffeineJar.flatMap { it.archiveFile }.map { it.asFile }
          val guavaJarFile = guavaJar.flatMap { it.archiveFile }.map { it.asFile }
          val relativeDir = projectDir
          val versions = libs.versions
          doFirst {
            systemProperties(mapOf(
              "caffeine-guava.osgi.jar" to guavaJarFile.get().relativeTo(relativeDir).path,
              "caffeine.osgi.jar" to caffeineJarFile.get().relativeTo(relativeDir).path,
              "guava.osgi.version" to versions.guava.get()))
          }
        }
      }
    }
  }
  val moduleTest by registering(JvmTestSuite::class) {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
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
