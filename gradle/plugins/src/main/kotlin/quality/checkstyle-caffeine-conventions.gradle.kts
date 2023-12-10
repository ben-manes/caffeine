plugins {
  checkstyle
}

val checkstyleConfig: Configuration by configurations.creating

configurations.checkstyle.configure {
  resolutionStrategy.dependencySubstitution {
    substitute(module("com.google.collections:google-collections"))
      .using(module(libs.guava.asProvider().get().toString()))
  }
}

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
  enabled = System.getProperties().containsKey("checkstyle")
  group = "Checkstyle"
  reports {
    xml.required = false
    html.required = true
  }
}
