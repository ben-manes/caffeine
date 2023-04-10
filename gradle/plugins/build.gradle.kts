import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  `kotlin-dsl`
  alias(libs.plugins.versions)
}
apply(from = "../constraints.gradle.kts")

java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))
val restrictions: List<MinimalExternalModuleDependency> by extra

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
  implementation(platform(libs.platforms.asm))
  implementation(platform(libs.platforms.junit5))
  implementation(platform(libs.platforms.kotlin))
  implementation(platform(libs.platforms.jackson))
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

  restrictions.forEach { library ->
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
