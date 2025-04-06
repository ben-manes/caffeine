import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

plugins {
  `jvm-ecosystem`
  id("de.thetaphi.forbiddenapis")
}

forbiddenApis {
  ignoreSignaturesOfMissingClasses = true
}

tasks.withType<CheckForbiddenApis>().configureEach {
  enabled = System.getProperties().containsKey("forbiddenApis")
  if (enabled) {
    forbiddenApis.failOnMissingClasses = !java.toolchain.languageVersion.get()
        .canCompileOrRun(JavaVersion.current().majorVersion.toInt())
    incompatibleWithConfigurationCache()
  }
}
