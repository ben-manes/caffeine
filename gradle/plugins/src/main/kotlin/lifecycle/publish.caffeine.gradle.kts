plugins {
  `maven-publish`
  `java-library`
  signing
}

java {
  withJavadocJar()
  withSourcesJar()
}

val testJar by tasks.registering(Jar::class) {
  group = "Build"
  description = "Assembles a jar archive containing the test classes."
  from(sourceSets.test.map { it.output })
  archiveClassifier = "test"
}

val testArtifacts: Configuration by configurations.creating
artifacts.add("testArtifacts", testJar)

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifact(testJar)

      pom {
        name = "Caffeine cache"
        description = project.description
        url = "https://github.com/ben-manes/caffeine"
        inceptionYear = "2014"

        scm {
          url = "https://github.com/ben-manes/caffeine"
          connection = "scm:git:https://github.com/ben-manes/caffeine.git"
          developerConnection = "scm:git:ssh://git@github.com/ben-manes/caffeine.git"
        }

        licenses {
          license {
            name = "Apache License, Version 2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            distribution = "repo"
          }
        }

        developers {
          developer {
            id = "ben-manes"
            name = "Ben Manes"
            email = "ben.manes@gmail.com"
            roles = listOf("owner", "developer")
          }
        }
      }
    }
  }
}

signing {
  // https://github.com/gradle/gradle/issues/11387
  setRequired { false }

  val signingKey: String? by project
  val signingKeyId: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
  sign(publishing.publications["mavenJava"])
}

tasks.withType<Sign>().configureEach {
  incompatibleWithConfigurationCache()
}
