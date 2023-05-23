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
  archiveClassifier.set("test")
}

val testArtifacts: Configuration by configurations.creating
artifacts.add("testArtifacts", testJar)

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifact(testJar)

      pom {
        name.set("Caffeine cache")
        description.set(project.description)
        url.set("https://github.com/ben-manes/caffeine")
        inceptionYear.set("2014")

        scm {
          url.set("https://github.com/ben-manes/caffeine")
          connection.set("scm:git:https://github.com/ben-manes/caffeine.git")
          developerConnection.set("scm:git:ssh://git@github.com/ben-manes/caffeine.git")
        }

        licenses {
          license {
            name.set("Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
        }

        developers {
          developer {
            id.set("ben-manes")
            name.set("Ben Manes")
            email.set("ben.manes@gmail.com")
            roles.set(listOf("owner", "developer"))
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
