import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  eclipse
  `java-library`
  alias(libs.plugins.versions)
}

dependencies {
  annotationProcessor(libs.hibernate.processor)

  implementation(libs.bundles.hibernate)
  implementation(libs.caffeine)

  runtimeOnly(libs.bundles.log4j2)
  runtimeOnly(libs.h2)

  testImplementation(libs.truth)
  testImplementation(libs.junit.jupiter)
}

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter()
  }
}

eclipse.classpath.file.beforeMerged {
  if (this is Classpath) {
    val absolutePath = layout.buildDirectory.dir("generated/sources/annotationProcessor/java/main")
    entries.add(SourceFolder(relativePath(absolutePath), "bin/main"))
  }
}

tasks.withType<DependencyUpdatesTask> {
  rejectVersionIf {
    fun isNonStable(version: String): Boolean {
      val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
      val regex = "^[0-9,.v-]+(-r)?$".toRegex()
      val isStable = stableKeyword || regex.matches(version)
      return isStable.not()
    }
    isNonStable(candidate.version) && !isNonStable(currentVersion)
  }
}
