import org.gradle.plugins.ide.eclipse.model.SourceFolder
import org.gradle.plugins.ide.eclipse.model.Classpath

plugins {
  eclipse
}

excludeInfoFiles()
setProjectEncoding()
ignoreDerivedResources()

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
fun excludeInfoFiles() {
  eclipse {
    classpath.file.whenMerged {
      if (this is Classpath) {
        entries.filterIsInstance<SourceFolder>().forEach { sourceFolder ->
          val excludes = sourceFolder.excludes.toMutableList()
          excludes += "module-info.java"
          if (sourceFolder.path != "src/main/java") {
            excludes += "**/package-info.java"
          }
          sourceFolder.excludes = excludes
        }
      }
    }
  }
}

fun ignoreDerivedResources() {
  eclipse {
    project.file.withXml {
      val directories = listOf("test-output", "build/classes",
        "build/reports", "build/jacoco", "build/tmp")
      val projectDescription = asNode()

      if (directories.any { file(it).exists() }) {
        val filter = projectDescription
          .appendNode("filteredResources")
          .appendNode("filter")
        filter.appendNode("id", System.currentTimeMillis().toString())
        filter.appendNode("type", "26")
        filter.appendNode("name")
        val matcher = filter.appendNode("matcher")
        matcher.appendNode("id", "org.eclipse.ui.ide.orFilterMatcher")
        val arguments = matcher.appendNode("arguments")
        directories.forEach {
          if (file(it).exists()) {
            val dirMatcher = arguments.appendNode("matcher")
            dirMatcher.appendNode("id", "org.eclipse.ui.ide.multiFilter")
            dirMatcher.appendNode("arguments", "1.0-projectRelativePath-matches-false-false-$it")
          }
        }
      }
    }
  }
}
