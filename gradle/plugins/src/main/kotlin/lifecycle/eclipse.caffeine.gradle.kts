import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  eclipse
}

val eclipsePreferences by tasks.registering {
  description = "Generates the Eclipse preferences files."
  incompatibleWithConfigurationCache()
  doFirst {
    addPreferences("org.eclipse.core.resources.prefs", mapOf(
      "encoding/<project>" to "UTF-8"))
    addPreferences("org.eclipse.jdt.core.prefs", mapOf(
      "org.eclipse.jdt.core.compiler.problem.unusedWarningToken" to "ignore",
      "org.eclipse.jdt.core.compiler.problem.unhandledWarningToken" to "ignore"))
  }
}

eclipse {
  classpath.file.whenMerged {
    if (this is Classpath) {
      excludeInfoFiles(this)
    }
  }
  synchronizationTasks(eclipsePreferences)
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
    settings.createNewFile()
  }
  val updates = preferences + ("eclipse.preferences.version" to "1")
  val text = settings.readLines()
    .filter { line -> line.substringBefore('=') !in updates }
    .plus(updates.map { (key, value) -> "$key=$value" })
    .joinToString(separator = "\n", postfix = "\n")
  settings.writeText(text)
}
