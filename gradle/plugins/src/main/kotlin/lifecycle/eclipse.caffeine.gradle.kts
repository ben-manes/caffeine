import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  eclipse
}

eclipse {
  classpath.file.whenMerged {
    if (this is Classpath) {
      excludeInfoFiles(this)
    }
  }
  addPreferences("org.eclipse.core.resources.prefs", mapOf(
    "encoding/<project>" to "UTF-8"))
  addPreferences("org.eclipse.jdt.core.prefs", mapOf(
    "org.eclipse.jdt.core.compiler.problem.unusedWarningToken" to "ignore"))
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

/** Adds preferences to the Eclipse project. */
fun addPreferences(path: String, preferences: Map<String, String>) {
  val settings = file(".settings/$path")
  if (!settings.exists()) {
    settings.parentFile.mkdirs()
    settings.writeText("eclipse.preferences.version=1\n")
  }
  val text = settings.readLines()
    .filter { line -> line.substringBefore('=') !in preferences }
    .plus(preferences.map { (key, value) -> "$key=$value" })
    .joinToString(separator = "\n", postfix = "\n")
  settings.writeText(text)
}
