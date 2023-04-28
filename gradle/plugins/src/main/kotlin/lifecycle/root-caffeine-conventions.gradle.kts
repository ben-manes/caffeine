plugins {
  id("base-caffeine-conventions")
  id("coverage-caffeine-conventions")
  id("sonarqube-caffeine-conventions")
  id("io.github.gradle-nexus.publish-plugin")
  id("dependency-check-caffeine-conventions")
  id("dependency-versions-caffeine-conventions")
}

nexusPublishing {
  repositories {
    sonatype()
  }
}
