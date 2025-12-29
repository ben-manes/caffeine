@file:Suppress("PackageDirectoryMismatch")
import org.gradle.util.GradleVersion.version

plugins {
  id("dev.sigstore.sign-base")
  `maven-publish`
  `java-library`
  signing
}

java {
  withJavadocJar()
  withSourcesJar()
}

configurations.sigstoreClientClasspath {
  resolutionStrategy.eachDependency {
    if ((requested.group == "io.grpc")
        && version(libs.versions.grpc.get()) >= version(requested.version)) {
      useVersion(libs.versions.grpc.get())
    }
  }
}

publishing {
  publications {
    register<MavenPublication>("mavenJava") {
      from(components["java"])

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

if (providers.environmentVariable("ACTIONS_ID_TOKEN_REQUEST_URL").isPresent) {
  publishing {
    sigstoreSign {
      sign(publications = publishing.publications)
    }
  }
}
