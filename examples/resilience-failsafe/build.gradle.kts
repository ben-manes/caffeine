plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.caffeine)
  implementation(libs.failsafe)

  testImplementation(libs.truth)
  testImplementation(libs.junit.jupiter)
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}
