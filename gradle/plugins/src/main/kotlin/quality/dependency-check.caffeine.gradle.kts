plugins {
  id("org.owasp.dependencycheck")
}

dependencyCheck {
  failOnError = false
  scanBuildEnv = true
  formats = listOf("HTML", "SARIF")
  nvd.apiKey = System.getenv("NVD_API_KEY")
}
