plugins {
  `java-library`
}

dependencies {
  implementation(libs.caffeine)
  implementation(libs.failsafe)

  testImplementation(libs.truth)
  testImplementation(libs.junit.jupiter)
}

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter()
  }
}
