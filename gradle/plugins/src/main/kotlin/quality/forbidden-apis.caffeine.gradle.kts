import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

plugins {
  `jvm-ecosystem`
  id("de.thetaphi.forbiddenapis")
}

forbiddenApis {
  ignoreSignaturesOfMissingClasses = true
}

tasks.withType<CheckForbiddenApis>().configureEach {
  val languageVersion = java.toolchain.languageVersion.get()
  enabled = rootProject.hasProperty("forbiddenApis")
  if (enabled) {
    forbiddenApis.failOnMissingClasses = !languageVersion.canCompileOrRun(
      JavaVersion.current().majorVersion.toInt())
    incompatibleWithConfigurationCache()
  }
}
