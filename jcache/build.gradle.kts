/** JCache compatibility adapter. */
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.Library

plugins {
  id("java-library.caffeine")
  id("revapi.caffeine")
}

sourceSets {
  register("testResources") {
    resources.srcDir("src/test/resources-extra")
  }
}

val jcacheJavadoc by configurations.registering
val jcacheTckTests by configurations.registering
val jcacheTckSources by configurations.registering

val testResourcesJar by tasks.registering(Jar::class) {
  from(sourceSets.named("testResources").map { it.output })
  archiveClassifier = "test-resources"
  outputs.cacheIf { true }
}

dependencies {
  api(project(":caffeine"))
  api(libs.osgi.annotations)
  api(libs.jakarta.inject)
  api(libs.jcache)
  api(libs.config)

  jcacheJavadoc(libs.jcache) {
    artifact { classifier = "javadoc" }
  }
  jcacheTckTests(libs.jcache.tck) {
    artifact { classifier = "tests" }
    isTransitive = false
  }
  jcacheTckSources(libs.jcache.tck) {
    artifact { classifier = "test-sources" }
    isTransitive = false
  }
}

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(libs.truth)
      implementation(libs.testng)
      implementation(libs.guice)
      implementation(libs.mockito)
      implementation(libs.awaitility)
      implementation(libs.jcache.guice)
      implementation(libs.guava.testlib)
      implementation(files(testResourcesJar))
      implementation(libs.nullaway.annotations)
      implementation.bundle(libs.bundles.slf4j.nop)
      implementation.bundle(libs.bundles.osgi.test.compile)
      implementation(libs.jcache.tck)
      implementation(libs.jcache.tck) {
        artifact { classifier = "tests" }
      }

      runtimeOnly(libs.junit.jupiter.testng)
      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets {
      named("test") {
        testTask.configure {
          systemProperty("testng.excludedGroups", "isolated")
        }
      }
      register("isolatedTest") {
        testTask.configure {
          forkEvery = 1
          systemProperty("testng.groups", "isolated")
          maxParallelForks = 2 * Runtime.getRuntime().availableProcessors()
        }
      }
      all {
        testTask.configure {
          inputs.files(unzipTestKit.map { it.outputs.files })
          testClassesDirs = files(sourceSets.named("test").map { it.output.classesDirs },
            layout.buildDirectory.files("tck"))
          classpath = files(sourceSets.named("test").map { it.runtimeClasspath })

          project(":caffeine").plugins.withId("java-library") {
            val caffeineJar = project(":caffeine").tasks.named<Jar>("jar")
            val jcacheJar = project(":jcache").tasks.named<Jar>("jar")
            inputs.files(caffeineJar.map { it.outputs.files })
            inputs.files(jcacheJar.map { it.outputs.files })

            val caffeineJarFile = caffeineJar.flatMap { it.archiveFile }.map { it.asFile }
            val jcacheJarFile = jcacheJar.flatMap { it.archiveFile }.map { it.asFile }
            val relativeDir = projectDir
            val versions = libs.versions
            doFirst {
              systemProperties(mapOf(
                // Test Compatibility Kit
                "java.net.preferIPv4Stack" to "true",
                "org.jsr107.tck.management.agentId" to "CaffeineMBeanServer",
                "javax.cache.Cache" to "com.github.benmanes.caffeine.jcache.CacheProxy",
                "javax.cache.Cache.Entry" to "com.github.benmanes.caffeine.jcache.EntryProxy",
                "javax.cache.CacheManager" to "com.github.benmanes.caffeine.jcache.CacheManagerImpl",
                "javax.management.builder.initial" to
                  "com.github.benmanes.caffeine.jcache.management.JCacheMBeanServerBuilder",

                // OSGi tests
                "config.osgi.version" to versions.config.get(),
                "jcache.osgi.version" to versions.jcache.get(),
                "felixScr.version" to versions.felix.scr.get(),
                "osgiUtil.promise" to versions.osgi.promise.get(),
                "osgiUtil.function" to versions.osgi.function.get(),
                "osgiService.component" to versions.osgi.annotations.get(),
                "caffeine.osgi.jar" to caffeineJarFile.get().relativeTo(relativeDir).path,
                "caffeine-jcache.osgi.jar" to jcacheJarFile.get().relativeTo(relativeDir).path))
            }
          }
        }
      }
    }
  }
}

val unzipTestKit by tasks.registering(Copy::class) {
  group = "Build"
  description = "Unzips the JCache TCK"
  from(jcacheTckTests.map { zipTree(it.singleFile) })
  into(layout.buildDirectory.dir("tck"))
  outputs.cacheIf { true }
}

val unzipJCacheJavaDoc by tasks.registering(Copy::class) {
  group = "Build"
  description = "Unzips the JCache JavaDoc"
  from(jcacheJavadoc.map { zipTree(it.singleFile) })
  into(layout.buildDirectory.dir("jcache-docs"))
}

tasks.named<Jar>("jar").configure {
  bundle.bnd(mapOf(
    "Automatic-Module-Name" to "com.github.benmanes.caffeine.jcache",
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine.jcache",
    "Import-Package" to listOf(
      "!org.jspecify.annotations.*",
      "!com.google.errorprone.annotations.*",
      "jakarta.inject.*;resolution:=optional",
      "*").joinToString(","),
    "Export-Package" to listOf(
      "com.github.benmanes.caffeine.jcache.spi;uses:=\"!org.jspecify.annotations\"",
      "com.github.benmanes.caffeine.jcache.copy;uses:=\"!org.jspecify.annotations\"",
      "com.github.benmanes.caffeine.jcache.configuration;uses:=\"!org.jspecify.annotations\""
    ).joinToString(","),
    "-exportcontents" to $$"${removeall;${packages;VERSIONED};${packages;CONDITIONAL}}"))
}

tasks.named<Javadoc>("javadoc").configure {
  inputs.files(unzipJCacheJavaDoc.map { it.outputs.files })
  javadocOptions {
    addBooleanOption("Xdoclint:all,-missing", true)
    linksOffline("https://static.javadoc.io/javax.cache/cache-api/${libs.versions.jcache.get()}/",
      relativePath(unzipJCacheJavaDoc.map { it.destinationDir }))
  }
}

tasks.withType<CheckForbiddenApis>().configureEach {
  bundledSignatures.addAll(when (name) {
    "forbiddenApisTest" -> listOf("jdk-deprecated", "jdk-internal",
      "jdk-non-portable", "jdk-reflection", "jdk-unsafe")
    else -> listOf("jdk-deprecated", "jdk-internal", "jdk-non-portable",
      "jdk-reflection", "jdk-system-out", "jdk-unsafe")
  })
}

eclipse {
  classpath.file.whenMerged {
    if (this is Classpath) {
      val regex = ".*cache-tests.*-tests.jar".toRegex()
      entries.filterIsInstance<Library>()
        .filter { regex.matches(it.path) }
        .forEach { it.sourcePath = fileReference(
          file(jcacheTckSources.map { sources -> sources.singleFile })) }
    }
  }
  synchronizationTasks(testResourcesJar)
}
