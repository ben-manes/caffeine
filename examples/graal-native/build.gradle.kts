plugins {
  id("application")
  alias(libs.plugins.graal)
  alias(libs.plugins.versions)
}

dependencies {
  implementation(caffeine())

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

application {
  mainClass.set("com.github.benmanes.caffeine.examples.graalnative.Application")
}

tasks.test {
  useJUnitPlatform()
}

graalvmNative {
  binaries.all {
    resources.autodetect()
  }
  toolchainDetection.set(false)
}

fun caffeine(): Any {
  if (System.getenv("SNAPSHOT") == "true") {
    return fileTree("../../caffeine/build/libs").also {
      require(!it.files.isEmpty()) { "Caffeine snapshot jar not found" }
    }
  }
  return libs.caffeine
}
