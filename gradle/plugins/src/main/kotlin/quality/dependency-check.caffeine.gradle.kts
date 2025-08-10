plugins {
  id("org.owasp.dependencycheck")
}

dependencyCheck {
  failOnError = false
  scanBuildEnv = true
  formats = listOf("HTML", "SARIF")
  nvd.apiKey = rootProject.findProperty("nvdApiKey") as? String
}
