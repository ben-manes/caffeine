import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

plugins {
  id("de.thetaphi.forbiddenapis")
}

forbiddenApis {
  ignoreSignaturesOfMissingClasses = true
}

tasks.withType<CheckForbiddenApis>().configureEach {
  enabled = System.getProperties().containsKey("forbiddenApis")
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}
