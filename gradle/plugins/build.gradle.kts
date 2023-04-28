import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  `kotlin-dsl`
  alias(libs.plugins.versions)
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))

dependencies {
  implementation(libs.bnd)
  implementation(libs.guava)
  implementation(libs.coveralls)
  implementation(libs.sonarqube)
  implementation(libs.bundles.jmh)
  implementation(libs.bundles.pmd)
  implementation(libs.forbiddenApis)
  implementation(libs.nexus.publish)
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

  libs.bundles.restrictions.get().forEach { library ->
    constraints.add("implementation", library.module.toString())
      .version { require(library.version!!) }
  }
}

tasks.withType<DependencyUpdatesTask> {
  val ignoredGroups = listOf("org.jetbrains.kotlin", "org.gradle.kotlin.kotlin-dsl")
  rejectVersionIf {
    (candidate.group in ignoredGroups) && (candidate.version != currentVersion)
  }
}
