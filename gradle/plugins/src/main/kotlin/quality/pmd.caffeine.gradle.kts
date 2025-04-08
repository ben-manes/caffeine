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

tasks.withType<Pmd>().configureEach {
  val isEnabled = providers.systemProperty("pmd")
  onlyIf { isEnabled.isPresent }
  group = "PMD"
  reports {
    xml.required = false
    html.required = true
  }
  isConsoleOutput = true
}

tasks.named<Pmd>("pmdTest").configure {
  ruleSetConfig = resources.text.fromFile(
    rootProject.layout.projectDirectory.file("gradle/config/pmd/rulesSets-test.xml"))
}
