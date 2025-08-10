import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.the

val Project.libs
  get() = the<LibrariesForLibs>()

fun Project.version(major: Int, minor: Int, patch: Int, releaseBuild: Boolean) {
  version = "$major.$minor.$patch" + if (releaseBuild) "" else "-SNAPSHOT"
}

fun Project.defaultJvmArgs(): ListProperty<String> {
  val jvmArgs = providers.gradleProperty("jvmArgs").map { it.split(",") }.orElse(emptyList())
  val arguments = providers.zip(jvmArgs, isGraalVM()) { args, isGraalVM ->
    buildList {
      if (isGraalVM) {
        add("-XX:+UnlockExperimentalVMOptions")
        add("-Dgraal.ShowConfiguration=info")
        add("-XX:+UseJVMCICompiler")
        add("-XX:+EnableJVMCI")
        add("-XX:+EagerJVMCI")
      }
      add("-Xmx4g")
      addAll(args)
    }
  }
  return objects.listProperty(String::class.java).value(arguments)
}

fun Project.isEarlyAccess(): Provider<Boolean> =
  providers.gradleProperty("earlyAccess").map { it == "true" }.orElse(false)
fun Project.isGraalVM(): Provider<Boolean> =
  providers.gradleProperty("graalvm").map { it == "true" }.orElse(false)
fun Project.isCI(): Provider<Boolean> =
  providers.environmentVariable("CI").map { true }.orElse(false)
fun Project.javaVersion(): Provider<String> =
  providers.gradleProperty("javaVersion").orElse(providers.environmentVariable("JAVA_VERSION"))
fun Project.javaTestVersion(): Provider<String> =
  providers.gradleProperty("javaTestVersion")
fun Project.javaDistribution(): Provider<String> =
  providers.gradleProperty("javaDistribution")
fun Project.javaVendor(): Provider<String> =
  providers.gradleProperty("javaVendor")

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
