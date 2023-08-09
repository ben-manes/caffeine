plugins {
  id("root-caffeine-conventions")
}

allprojects {
  description = "A high performance caching library"
  group = "com.github.ben-manes.caffeine"
  version(
    major = 3, // incompatible API changes
    minor = 1, // backwards-compatible additions
    patch = 9, // backwards-compatible bug fixes
    releaseBuild = rootProject.hasProperty("release"))
}
