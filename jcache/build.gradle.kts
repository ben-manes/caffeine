/** JCache compatibility adapter. */
@file:Suppress("UnstableApiUsage")
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import org.gradle.plugins.ide.eclipse.model.Library
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath

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
val caffeineOsgiBundle by configurations.registering {
  configureAsRuntimeIncoming()
}

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

  caffeineOsgiBundle(project(":caffeine", "osgiBundleElements"))

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
      implementation(libs.guice)
      implementation(libs.truth)
      implementation(libs.mockito)
      implementation(libs.awaitility)
      implementation(libs.jcache.guice)
      implementation(libs.guava.testlib)
      implementation(libs.infer.annotations)
      implementation(files(testResourcesJar))
      implementation(libs.nullaway.annotations)
      implementation(libs.spotbugs.annotations)

      runtimeOnly.bundle(libs.bundles.slf4j.nop)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets {
      named("test") {
        testTask.configure {
          useJUnitPlatform {
            excludeTags("isolated")
          }
        }
      }
      register("isolatedTest") {
        testTask.configure {
          useJUnitPlatform {
            includeTags("isolated")
          }
          forkEvery = 1
          maxParallelForks = 2 * Runtime.getRuntime().availableProcessors()
        }
      }
    }
  }
  register<JvmTestSuite>("osgiTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation.bundle(libs.bundles.osgi.test.compile)

      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets.all {
      testTask.configure {
        val caffeineOsgiJarFile = layout.file(caffeineOsgiBundle.map { it.singleFile })
        val jcacheJarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
        inputs.files(caffeineOsgiJarFile)
        inputs.files(jcacheJarFile)

        val relativeDir = projectDir
        val versions = libs.versions
        doFirst {
          systemProperties(mapOf(
            "config.osgi.version" to versions.config.get(),
            "jcache.osgi.version" to versions.jcache.get(),
            "felixScr.version" to versions.felix.scr.get(),
            "osgiUtil.promise" to versions.osgi.promise.get(),
            "osgiUtil.function" to versions.osgi.function.get(),
            "osgiService.component" to versions.osgi.annotations.get(),
            "caffeine.osgi.jar" to caffeineOsgiJarFile.relativePathFrom(relativeDir).get(),
            "caffeine-jcache.osgi.jar" to jcacheJarFile.relativePathFrom(relativeDir).get()))
        }
      }
    }
  }
  register<JvmTestSuite>("tckTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())

      runtimeOnly(libs.hamcrest)
      runtimeOnly(libs.jcache.tck)
      runtimeOnly(libs.jcache.tck) {
        artifact { classifier = "tests" }
      }

      runtimeOnly(libs.junit.jupiter.vintage)
    }
    targets.all {
      testTask.configure {
        inputs.files(unzipTestKit.map { it.outputs.files })
        testClassesDirs = layout.buildDirectory.files("tck")

        doFirst {
          val pkg = "com.github.benmanes.caffeine.jcache"
          systemProperties(mapOf(
            "java.net.preferIPv4Stack" to "true",
            "javax.cache.Cache" to "$pkg.CacheProxy",
            "javax.cache.Cache.Entry" to "$pkg.EntryProxy",
            "javax.cache.CacheManager" to "$pkg.CacheManagerImpl",
            "org.jsr107.tck.management.agentId" to "CaffeineMBeanServer",
            "javax.management.builder.initial" to "$pkg.management.JCacheMBeanServerBuilder"))
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
    if (this is EclipseClasspath) {
      val regex = ".*cache-tests.*-tests.jar".toRegex()
      entries.filterIsInstance<Library>()
        .filter { regex.matches(it.path) }
        .forEach { it.sourcePath = fileReference(
          file(jcacheTckSources.map { sources -> sources.singleFile })) }
    }
  }
  synchronizationTasks(testResourcesJar)
}
