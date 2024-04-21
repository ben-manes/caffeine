plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.caffeine)
  implementation(libs.rxjava)

  testImplementation(libs.awaitility)
  testImplementation(libs.junit)
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}

java.toolchain.languageVersion = JavaLanguageVersion.of(
  System.getenv("JAVA_VERSION")?.toIntOrNull() ?: 17)

tasks.withType<JavaCompile>().configureEach {
  javaCompiler = javaToolchains.compilerFor {
    languageVersion = java.toolchain.languageVersion
  }
}
