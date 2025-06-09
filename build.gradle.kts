plugins {
  id("root.caffeine")
}

allprojects {
  description = "A high performance caching library"
  group = "com.github.ben-manes.caffeine"
  version(
    major = 3, // incompatible API changes
    minor = 2, // backwards-compatible additions
    patch = 2, // backwards-compatible bug fixes
    releaseBuild = rootProject.hasProperty("release"))
}
