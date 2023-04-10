import aQute.bnd.gradle.BundleTaskExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.the

val Project.libs get() = the<LibrariesForLibs>()

fun Project.version(major: Int, minor: Int, patch: Int, releaseBuild: Boolean) {
  version = "$major.$minor.$patch" + if (releaseBuild) "" else "-SNAPSHOT"
}

/** Adds the osgi manifest attributes to the jar task. */
fun Project.applyOsgi(task: Jar, instructions: Map<String, Any>) {
  plugins.withId("java-library") {
    val extension = the<JavaPluginExtension>()
    if (task.extensions.findByType(BundleTaskExtension::class.java) == null) {
      task.extensions.create(BundleTaskExtension.NAME, BundleTaskExtension::class.java, task)
    }
    val defaults = mapOf(
      "Bundle-License" to "https://www.apache.org/licenses/LICENSE-2.0",
      "Build-Jdk-Spec" to extension.toolchain.languageVersion.get(),
      "Implementation-Title" to description,
      "Bundle-Description" to description,
      "Implementation-Version" to version,
      "-noextraheaders" to true,
      "-reproducible" to true,
      "-snapshot" to "SNAPSHOT")
    task.extensions.configure<BundleTaskExtension>(BundleTaskExtension.NAME) {
      properties.set(provider {
        mapOf("project.description" to project.description)
      })
      bnd(defaults + instructions)
    }
  }
}

fun Project.javaExecJvmArgs(): List<String> {
  val jvmArgs = mutableListOf("-XX:+UseParallelGC", "-Xmx4g")
  val arguments = findProperty("jvmArgs") as String?
  if (arguments != null) {
    jvmArgs.addAll(arguments.split(","))
  }
  return jvmArgs
}

fun caffeineSystemProperties(): Map<String, Any> {
  return System.getProperties().stringPropertyNames()
    .filter { it.startsWith("caffeine") }
    .associateWith { System.getProperties().getProperty(it) }
}

val DisableStrongEncapsulationJvmArgs = listOf(
  "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
  "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
  "--add-opens",   "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
  "--add-opens",   "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
  "--add-opens",   "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
