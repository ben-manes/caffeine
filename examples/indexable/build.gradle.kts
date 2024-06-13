plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.caffeine)
  implementation(libs.guava)

  testImplementation(libs.junit.jupiter)
  testImplementation(libs.guava.testlib)
  testImplementation(libs.truth)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}
