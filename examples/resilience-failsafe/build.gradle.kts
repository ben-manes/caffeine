plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.caffeine)
  implementation(libs.failsafe)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}
