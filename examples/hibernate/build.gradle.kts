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
  implementation(libs.bundles.log4j2)
  implementation(libs.caffeine)
  runtimeOnly(libs.h2)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

testing.suites {
  val test by getting(JvmTestSuite::class) {
    useJUnitJupiter()
  }
}

eclipse.classpath.file.beforeMerged {
  if (this is Classpath) {
    val absolutePath = layout.buildDirectory.dir("generated/sources/annotationProcessor/java/main")
    entries.add(SourceFolder(relativePath(absolutePath), "bin/main"))
  }
}
