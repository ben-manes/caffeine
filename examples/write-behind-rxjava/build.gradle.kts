plugins {
  `java-library`
}

dependencies {
  implementation(libs.rxjava)
  implementation(libs.caffeine)

  testImplementation(libs.awaitility)
  testImplementation(libs.junit.jupiter)
}

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter()
  }
}
