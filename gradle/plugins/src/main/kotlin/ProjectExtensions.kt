import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the

val Project.libs
  get() = the<LibrariesForLibs>()

fun Project.version(major: Int, minor: Int, patch: Int, releaseBuild: Boolean) {
  version = "$major.$minor.$patch" + if (releaseBuild) "" else "-SNAPSHOT"
}

fun Project.defaultJvmArgs(): List<String> {
  val jvmArgs = mutableListOf("-Xmx4g")
  if (System.getenv("GRAALVM") == "true") {
    jvmArgs += listOf(
      "-XX:+UnlockExperimentalVMOptions", "-Dgraal.ShowConfiguration=info",
      "-XX:+EnableJVMCI", "-XX:+UseJVMCICompiler", "-XX:+EagerJVMCI")
  }
  val arguments = findProperty("jvmArgs") as String?
  if (arguments != null) {
    jvmArgs += arguments.split(",")
  }
  return jvmArgs
}

fun caffeineSystemProperties(): Map<String, Any> =
  System.getProperties()
    .stringPropertyNames()
    .filter { it.startsWith("caffeine") }
    .associateWith { System.getProperties().getProperty(it) }
fun isEarlyAccess(): Boolean = System.getenv("JDK_EA") == "true"
fun isCI(): Boolean = !System.getenv("CI").isNullOrEmpty()

val DisableStrongEncapsulationJvmArgs = buildList {
  listOf("api", "code", "file", "main", "parser", "processing", "tree", "util").forEach {
    add("--add-exports")
    add("jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED")
  }
  listOf("code", "comp", "parser").forEach {
    add("--add-opens")
    add("jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED")
  }
}
