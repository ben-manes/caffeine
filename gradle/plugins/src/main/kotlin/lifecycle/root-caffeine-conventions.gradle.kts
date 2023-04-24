plugins {
  id("base-caffeine-conventions")
  id("coverage-caffeine-conventions")
  id("sonarqube-caffeine-conventions")
  id("dependency-check-caffeine-conventions")
  id("io.github.gradle-nexus.publish-plugin")
  id("dependency-versions-caffeine-conventions")
}

nexusPublishing {
  repositories {
    sonatype()
  }
}
