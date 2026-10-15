plugins {
  id("application")
}

dependencies {
  implementation(libs.caffeine)

  testImplementation(libs.truth)
  testImplementation(libs.junit.jupiter)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

application {
  mainClass = "com.github.benmanes.caffeine.examples.virtualthreads.VirtualThreadCacheExample"
}

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter()
  }
}
