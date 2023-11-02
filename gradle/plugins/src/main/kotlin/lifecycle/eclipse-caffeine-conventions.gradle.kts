import org.gradle.plugins.ide.eclipse.GenerateEclipseJdt
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  eclipse
  id("com.diffplug.eclipse.resourcefilters")
}

eclipseResourceFilters {
  val directories = listOf("test-output", "build/classes",
    "build/reports", "build/jacoco", "build/tmp")
  for (dir in directories) {
    exclude().folders().name(dir)
  }
}

eclipse.classpath.file.whenMerged {
  if (this is Classpath) {
    excludeInfoFiles(this)
  }
}

tasks.withType<GenerateEclipseJdt>().configureEach {
  doLast {
    setProjectEncoding()
  }
}

/** Specifies the content encoding for the Eclipse project. */
fun setProjectEncoding() {
  val prefs = file(".settings/org.eclipse.core.resources.prefs")
  if (!prefs.exists()) {
    prefs.parentFile.mkdirs()
    prefs.writeText("eclipse.preferences.version=1\n")
  }
  if (!prefs.readText().contains("encoding/<project>")) {
    prefs.appendText("encoding/<project>=UTF-8\n")
  }
}

/** Exclude module-info and package-info when compiling through Eclipse. */
fun excludeInfoFiles(classpath: Classpath) {
  classpath.entries.filterIsInstance<SourceFolder>().forEach { sourceFolder ->
    val excludes = sourceFolder.excludes.toMutableList()
    excludes += "module-info.java"
    if (sourceFolder.path != "src/main/java") {
      excludes += "**/package-info.java"
    }
    sourceFolder.excludes = excludes
  }
}
