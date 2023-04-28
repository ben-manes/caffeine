/** Guava compatibility adapter. The tests are forked from Guava commit e370dde. */
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

plugins {
  id("java-library-caffeine-conventions")
}

dependencies {
  api(project(":caffeine"))
  api(libs.guava)

  testImplementation(libs.jctools)
  testImplementation(libs.guava.testlib)
  testImplementation(libs.bundles.slf4j.nop)
}

tasks.named<JavaCompile>("compileJava").configure {
  modularity.inferModulePath.set(true)
}

tasks.withType<Test>().configureEach {
  project(":caffeine").plugins.withId("java-library") {
    val caffeineJar = project(":caffeine").tasks.jar
    val guavaJar = project(":guava").tasks.jar
    dependsOn(caffeineJar, guavaJar)

    useJUnitPlatform()
    systemProperties(mapOf(
      "guava.osgi.version" to libs.versions.guava.get(),
      "caffeine.osgi.jar" to relativePath(caffeineJar.get().archiveFile.get().asFile.path),
      "caffeine-guava.osgi.jar" to relativePath(guavaJar.get().archiveFile.get().asFile.path),
    ))
  }
}

tasks.withType<Jar>().configureEach {
  applyOsgi(this, mapOf(
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine.guava",
    "Import-Package" to listOf(
      "com.google.common.cache",
      "com.google.common.util.concurrent",
      "com.github.benmanes.caffeine.cache",
      "com.github.benmanes.caffeine.cache.stats").joinToString(","),
    "Export-Package" to "com.github.benmanes.caffeine.guava",
    "Automatic-Module-Name" to "com.github.benmanes.caffeine.guava"))
}

tasks.withType<Javadoc>().configureEach {
  javadocOptions {
    addStringOption("Xdoclint:none", "-quiet")
  }
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisTest").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-system-out", "jdk-unsafe"))
}
