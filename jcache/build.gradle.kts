/** JCache compatibility adapter. */
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.Library

plugins {
  id("java-library-caffeine-conventions")
}

sourceSets {
  create("testResources") {
    resources.srcDir("src/test/resources-extra")
  }
}

val jcacheJavadoc: Configuration by configurations.creating
val jcacheTckTests: Configuration by configurations.creating
val jcacheTckSources: Configuration by configurations.creating

val testResourcesJar by tasks.registering(Jar::class) {
  from(sourceSets["testResources"].output)
  archiveClassifier = "test-resources"
  outputs.cacheIf { true }
}

dependencies {
  api(project(":caffeine"))
  api(libs.osgi.annotations)
  api(libs.jakarta.inject)
  api(libs.jcache)
  api(libs.config)

  testImplementation(libs.guava)
  testImplementation(libs.jcache.guice)
  testImplementation(libs.guava.testlib)
  testImplementation(libs.bundles.slf4j.nop)
  testImplementation(files(testResourcesJar))
  testImplementation(libs.jcache.tck)
  testImplementation(libs.jcache.tck) {
    artifact {
      classifier = "tests"
    }
  }

  jcacheJavadoc(libs.jcache) {
    artifact {
      classifier = "javadoc"
    }
  }
  jcacheTckTests(libs.jcache.tck) {
    artifact {
      classifier = "tests"
    }
    isTransitive = false
  }
  jcacheTckSources(libs.jcache.tck) {
    artifact {
      classifier = "test-sources"
    }
    isTransitive = false
  }
}

val unzipTestKit by tasks.registering(Copy::class) {
  group = "Build"
  description = "Unzips the JCache TCK"
  from(jcacheTckTests.map { zipTree(it) })
  into(layout.buildDirectory.dir("tck"))
  outputs.cacheIf { true }
}

val unzipJCacheJavaDoc by tasks.registering(Copy::class) {
  group = "Build"
  description = "Unzips the JCache JavaDoc"
  from(jcacheJavadoc.map { zipTree(it) })
  into(layout.buildDirectory.dir("jcache-docs"))
}

tasks.named<JavaCompile>("compileJava").configure {
  modularity.inferModulePath = true
}

tasks.jar {
  bundle.bnd(mapOf(
    "Automatic-Module-Name" to "com.github.benmanes.caffeine.jcache",
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine.jcache",
    "Import-Package" to listOf(
      "!org.checkerframework.*",
      "!com.google.errorprone.annotations.*",
      "jakarta.inject.*;resolution:=\"optional\"",
      "*").joinToString(","),
    "Export-Package" to listOf(
      "com.github.benmanes.caffeine.jcache.spi",
      "com.github.benmanes.caffeine.jcache.copy",
      "com.github.benmanes.caffeine.jcache.configuration").joinToString(","),
    "-exportcontents" to "\${removeall;\${packages;VERSIONED};\${packages;CONDITIONAL}}"))
}

tasks.named<Javadoc>("javadoc").configure {
  dependsOn(unzipJCacheJavaDoc)
  javadocOptions {
    addStringOption("Xdoclint:none", "-quiet")
    linksOffline("https://static.javadoc.io/javax.cache/cache-api/${libs.versions.jcache.get()}/",
      relativePath(unzipJCacheJavaDoc.map { it.destinationDir }))
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  dependsOn(unzipTestKit)
  testClassesDirs += layout.buildDirectory.files("tck")

  project(":caffeine").plugins.withId("java-library") {
    val caffeineJar = project(":caffeine").tasks.named<Jar>("jar")
    val jcacheJar = project(":jcache").tasks.named<Jar>("jar")
    dependsOn(caffeineJar, jcacheJar)

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
      "config.osgi.version" to libs.versions.config.get(),
      "jcache.osgi.version" to libs.versions.jcache.get(),
      "felixScr.version" to libs.versions.felix.scr.get(),
      "osgiUtil.promise" to libs.versions.osgi.promise.get(),
      "osgiUtil.function" to libs.versions.osgi.function.get(),
      "osgiService.component" to libs.versions.osgi.annotations.get(),
      "caffeine.osgi.jar" to relativePath(caffeineJar.get().archiveFile.get().asFile.path),
      "caffeine-jcache.osgi.jar" to relativePath(jcacheJar.get().archiveFile.get().asFile.path)))
  }
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisTest").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-unsafe"))
}

eclipse.classpath.file.whenMerged {
  if (this is Classpath) {
    val regex = ".*cache-tests.*-tests.jar".toRegex()
    entries.filterIsInstance<Library>()
      .filter { regex.matches(it.path) }
      .forEach { it.sourcePath = fileReference(file(jcacheTckSources.asPath)) }
  }
}
