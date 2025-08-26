/** Guava compatibility adapter. The tests are forked from Guava commit e370dde. */
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import net.ltgt.gradle.errorprone.errorprone

plugins {
  id("java-library.caffeine")
  id("revapi.caffeine")
}

dependencies {
  api(project(":caffeine"))
  api(libs.guava)

  testImplementation(libs.guava.testlib)
  testImplementation(libs.bundles.slf4j.nop)
  testImplementation(libs.bundles.osgi.test.compile)

  testRuntimeOnly(libs.bundles.osgi.test.runtime)
  testRuntimeOnly(libs.bundles.junit.engines)
}

tasks.named<JavaCompile>("compileJava").configure {
  options.compilerArgs.add("-Xlint:-exports")
}

tasks.named<JavaCompile>("compileTestJava").configure {
  options.errorprone {
    disable("Varifier")
    disable("Var")
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()

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

tasks.named<Jar>("jar").configure {
  bundle.bnd(mapOf(
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine.guava",
    "Import-Package" to listOf(
      "com.github.benmanes.caffeine.*",
      "com.google.common.*;version=23.2").joinToString(","),
    "Export-Package" to "com.github.benmanes.caffeine.guava",
    "Automatic-Module-Name" to "com.github.benmanes.caffeine.guava"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisTest").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-system-out", "jdk-unsafe"))
}
