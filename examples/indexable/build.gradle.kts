plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.guava)
  implementation(libs.caffeine)

  testImplementation(libs.truth)
  testImplementation(libs.guava.testlib)
  testImplementation(libs.junit.jupiter)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}
