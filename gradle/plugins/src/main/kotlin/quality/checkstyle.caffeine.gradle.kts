plugins {
  checkstyle
}

val checkstyleConfig: Configuration by configurations.creating

dependencies {
  checkstyleConfig(libs.checkstyle) {
    isTransitive = false
  }
}

checkstyle {
  toolVersion = libs.versions.checkstyle.get()
  config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
}

tasks.withType<Checkstyle>().configureEach {
  val isEnabled = providers.systemProperty("checkstyle")
  onlyIf { isEnabled.isPresent }
  group = "Checkstyle"
  reports {
    xml.required = false
    html.required = true
  }
}
