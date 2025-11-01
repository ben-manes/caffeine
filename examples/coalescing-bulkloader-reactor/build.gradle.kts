plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.caffeine)
  implementation(libs.reactor)

  testImplementation(libs.truth)
  testImplementation(libs.junit.jupiter)
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
