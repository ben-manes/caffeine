plugins {
  id("base.caffeine")
  id("coverage.caffeine")
  id("versions.caffeine")
  id("sonarqube.caffeine")
  id("dependency-check.caffeine")
  id("io.github.gradle-nexus.publish-plugin")

  // https://youtrack.jetbrains.com/issue/QD-11954
  // id("intellij.caffeine")
}

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
      snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}
