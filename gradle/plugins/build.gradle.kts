import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  `kotlin-dsl`
  alias(libs.plugins.versions)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(11)

dependencies {
  implementation(libs.bnd)
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
  implementation(platform(libs.asm.bom))
  implementation(platform(libs.junit5.bom))
  implementation(platform(libs.kotlin.bom))
  implementation(platform(libs.jackson.bom))
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
  implementation(libs.coveralls) {
    exclude(group = "net.sourceforge.nekohtml", module = "nekohtml")
  }

  libs.bundles.constraints.get().forEach { library ->
    constraints.add("implementation", library.module.toString())
      .version { require(library.version!!) }
  }
}

tasks.withType<DependencyUpdatesTask> {
  resolutionStrategy {
    componentSelection {
      val ignoredGroups = listOf("org.jetbrains.kotlin", "org.gradle.kotlin.kotlin-dsl")
      all {
        if ((candidate.group in ignoredGroups) && (candidate.version != currentVersion)) {
          reject("kotlin dsl")
        }
      }
    }
    force(libs.bnd)
  }
}
