plugins {
  id("org.owasp.dependencycheck")
}

dependencyCheck {
  failOnError = false
  scanBuildEnv = true
  formats = listOf("HTML", "SARIF")
}

subprojects {
  tasks.withType<Jar>().configureEach {
    rootProject.tasks.dependencyCheckAggregate.get().dependsOn(this)
  }
}
