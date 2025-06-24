import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent

buildscript {
  dependencies {
    classpath(platform(libs.okio.bom))
    classpath(platform(libs.okhttp.bom))
    libs.bundles.constraints.get().forEach { library ->
      constraints.add("classpath", library.module.toString())
        .version { require(library.version!!) }
    }
  }
}

plugins {
  `kotlin-dsl`
  alias(libs.plugins.versions)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

dependencies {
  implementation(libs.jmh)
  implementation(libs.guava)
  implementation(libs.bundles.pmd)

  implementation(plugin(libs.plugins.bnd))
  implementation(plugin(libs.plugins.idea))
  implementation(plugin(libs.plugins.nullaway))
  implementation(plugin(libs.plugins.sigstore))
  implementation(plugin(libs.plugins.spotbugs))
  implementation(plugin(libs.plugins.versions))
  implementation(plugin(libs.plugins.coveralls))
  implementation(plugin(libs.plugins.sonarqube))
  implementation(plugin(libs.plugins.jmh.report))
  implementation(plugin(libs.plugins.errorprone))
  implementation(plugin(libs.plugins.nexus.publish))
  implementation(plugin(libs.plugins.forbidden.apis))
  implementation(plugin(libs.plugins.jmh.asProvider()))
  implementation(plugin(libs.plugins.dependency.check))
  implementation(plugin(libs.plugins.jvm.dependency.conflict.resolution))

  implementation(platform(libs.asm.bom))
  implementation(platform(libs.okio.bom))
  implementation(platform(libs.kotlin.bom))
  implementation(platform(libs.okhttp.bom))
  implementation(platform(libs.jackson.bom))
  implementation(platform(libs.junit.jupiter.bom))
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

  libs.bundles.constraints.get().forEach { library ->
    constraints.add("implementation", library.module.toString())
      .version { require(library.version!!) }
  }
}

tasks.named<Jar>("jar").configure {
  outputs.cacheIf { true }
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
  checkBuildEnvironmentConstraints = true
  checkConstraints = true
  resolutionStrategy {
    componentSelection {
      val ignoredGroups = listOf("com.beust", "org.apache.logging.log4j",
        "org.jetbrains.kotlin", "org.gradle.kotlin.kotlin-dsl")
      val stable = setOf("com.fasterxml.jackson", "com.google.protobuf", "com.squareup.okhttp3")
      val isNonStable = "^[0-9,.v-]+(-r)?$".toRegex()
      all(Action<ComponentSelectionWithCurrent> {
        if ((candidate.group in ignoredGroups) && (candidate.version != currentVersion)) {
          reject("Internal dependency")
        } else if ((candidate.group in stable) && !isNonStable.matches(candidate.version)) {
          reject("Release candidate")
        }
      })
    }
  }
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> {
  // https://docs.gradle.org/current/userguide/plugins.html#sec:plugin_markers
  return plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
}

fun setProjectEncoding() {
  val prefs = file(".settings/org.eclipse.core.resources.prefs")
  if (!prefs.exists()) {
    prefs.parentFile.mkdirs()
    prefs.writeText("eclipse.preferences.version=1\n")
  }
  if ("encoding/<project>" !in prefs.readText()) {
    prefs.appendText("encoding/<project>=UTF-8\n")
  }
}

if (System.getenv("CI").isNullOrEmpty()) {
  setProjectEncoding()
}
