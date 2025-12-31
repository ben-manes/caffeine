/** Java object layout: https://github.com/openjdk/jol */
@file:Suppress("PackageDirectoryMismatch")
plugins {
  `java-library`
}

val objectLayout by configurations.registering
dependencies {
  objectLayout(project)
  objectLayout(libs.java.`object`.layout)
}

val modes = mapOf(
  "externals" to "Shows objects reachable from a given instance",
  "estimates" to "Simulate the class layout in different VM modes",
  "internals" to "Show the field layout, default contents, and object header",
  "footprint" to "Estimate the footprint of all objects reachable from a given instance",
)
modes.forEach { (mode, details) ->
  tasks.register<JavaExec>(mode) {
    group = "Object Layout"
    description = details
    classpath(objectLayout)
    mainClass = "org.openjdk.jol.Main"
    incompatibleWithConfigurationCache()
    inputs.files(tasks.named<JavaCompile>("compileJava").map { it.outputs.files })
    argumentProviders.add {
      var className = findProperty("className") as? String
      val base = "com.github.benmanes.caffeine.cache"
      require(className != null) { "Usage: $name -PclassName=$base.[CLASS_NAME]" }
      if (!className.startsWith("com") && !className.startsWith("java")) {
        className = "$base.$className"
      }
      listOf(name, className)
    }
  }
}
