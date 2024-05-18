import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

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

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

dependencies {
  implementation(libs.bnd)
  implementation(libs.idea)
  implementation(libs.guava)
  implementation(libs.sonarqube)
  implementation(libs.bundles.jmh)
  implementation(libs.bundles.pmd)
  implementation(libs.nexus.publish)
  implementation(libs.forbidden.apis)
  implementation(libs.nullaway.plugin)
  implementation(libs.spotbugs.plugin)
  implementation(libs.dependency.check)
  implementation(libs.errorprone.plugin)
  implementation(libs.dependency.versions)
  implementation(libs.jvm.dependency.conflict.resolution)
  implementation(libs.coveralls) {
    exclude(group = "net.sourceforge.nekohtml", module = "nekohtml")
  }

  implementation(platform(libs.asm.bom))
  implementation(platform(libs.okio.bom))
  implementation(platform(libs.junit5.bom))
  implementation(platform(libs.kotlin.bom))
  implementation(platform(libs.okhttp.bom))
  implementation(platform(libs.jackson.bom))
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

  libs.bundles.constraints.get().forEach { library ->
    constraints.add("implementation", library.module.toString())
      .version { require(library.version!!) }
  }
}

tasks.withType<DependencyUpdatesTask> {
  checkBuildEnvironmentConstraints = true
  checkConstraints = true
  resolutionStrategy {
    componentSelection {
      val ignoredGroups = listOf("org.jetbrains.kotlin", "org.gradle.kotlin.kotlin-dsl")
      val stable = setOf("com.fasterxml.jackson", "com.squareup.okhttp3")
      val isNonStable = "^[0-9,.v-]+(-r)?$".toRegex()
      all {
        if ((candidate.group in ignoredGroups) && (candidate.version != currentVersion)) {
          reject("kotlin dsl")
        } else if ((candidate.group in stable) && !isNonStable.matches(candidate.version)) {
          reject("Release candidate")
        }
      }
    }
  }
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

setProjectEncoding()
