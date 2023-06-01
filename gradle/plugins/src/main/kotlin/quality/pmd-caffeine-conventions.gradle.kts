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
  enabled = System.getProperties().containsKey("pmd")
  group = "PMD"
  reports {
    xml.required = false
    html.required = true
  }
  isConsoleOutput = true
}
