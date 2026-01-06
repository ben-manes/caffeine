/** Java object layout: https://github.com/openjdk/jol */
@file:Suppress("PackageDirectoryMismatch")
plugins {
  `java-library`
}

val compileJava by tasks.existing
val objectLayout by configurations.registering

dependencies {
  objectLayout(project)
  objectLayout(libs.java.`object`.layout)
}

mapOf(
  "estimates" to "Shows objects reachable from a given instance",
  "externals" to "Shows objects reachable from a given instance",
  "internals" to "Show the field layout, default contents, and object header",
  "footprint" to "Estimate the footprint of all objects reachable from a given instance"
).forEach { (name, details) ->
  tasks.register<JavaObjectLayoutTask>(name) {
    compileClasspath = compileJava.map { it.outputs.files }
    classpath(objectLayout)
    description = details
  }
}

@CacheableTask
abstract class JavaObjectLayoutTask : JavaExec() {
  @get:Input @get:Optional
  @get:Option(option = "class", description = "The class to evaluate")
  abstract val className: Property<String>
  @get:InputFiles @get:CompileClasspath
  abstract val compileClasspath: Property<FileCollection>

  init {
    group = "Object Layout"
    mainClass = "org.openjdk.jol.Main"
    systemProperties("jdk.attach.allowAttachSelf" to "true",
      "jdk.instrument.traceUsage" to "true", "jol.tryWithSudo" to "true")
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-XX:+UseCompactObjectHeaders")
    argumentProviders.add {
      val base = "com.github.benmanes.caffeine.cache"
      require(className.isPresent) { "Usage: $name -class=$base.[CLASS_NAME]" }
      require(compileClasspath.get().asFileTree
        .any { it.path.contains(className.get().replace('.', File.separatorChar)) }) {
          "${className.get()} not found in classpath"
        }
      listOfNotNull(name, className.map { if (it.contains('.')) it else "$base.$it" }.orNull)
    }
  }
}
