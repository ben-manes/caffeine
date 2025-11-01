plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.rxjava)
  implementation(libs.caffeine)

  testImplementation(libs.awaitility)
  testImplementation(libs.junit.jupiter)
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}
