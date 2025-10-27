plugins {
  pmd
}

dependencies {
  pmd(libs.bundles.pmd)
}

pmd {
  ruleSets = emptyList()
  toolVersion = libs.versions.pmd.get()
  ruleSetConfig = resources.text.fromFile(
    rootProject.layout.projectDirectory.file("gradle/config/pmd/rulesSets.xml"))
}

tasks.register("pmd") {
  group = "PMD"
  description = "Run all PMD checks."
  dependsOn(tasks.withType<Pmd>())
}

tasks.withType<Pmd>().configureEach {
  val isEnabled = providers.gradleProperty("pmd")
  onlyIf { isEnabled.isPresent }
  group = "PMD"
  reports {
    xml.required = false
    html.required = true
  }
  isConsoleOutput = true

  if (name.contains("Test")) {
    ruleSetConfig = resources.text.fromFile(
      rootProject.layout.projectDirectory.file("gradle/config/pmd/rulesSets-test.xml"))
  }
}
