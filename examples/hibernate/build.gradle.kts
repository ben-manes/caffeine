plugins {
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  implementation(libs.bundles.hibernate)
  implementation(libs.caffeine)
  implementation(libs.slf4j)
  runtimeOnly(libs.h2)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

tasks.test {
  useJUnitPlatform()
}

java.toolchain.languageVersion = JavaLanguageVersion.of(System.getenv("JAVA_VERSION") ?: "11")

tasks.withType<JavaCompile>().configureEach {
  javaCompiler = javaToolchains.compilerFor {
    languageVersion = java.toolchain.languageVersion
  }
}
