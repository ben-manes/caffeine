/** Java object layout: https://github.com/openjdk/jol */
plugins {
  `java-library`
}

val objectLayout: Configuration by configurations.creating
dependencies {
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
    dependsOn(tasks.compileJava)
    mainClass = "org.openjdk.jol.Main"
    incompatibleWithConfigurationCache()
    classpath(objectLayout, sourceSets.main.map { it.runtimeClasspath })

    doFirst {
      var className = findProperty("className") as String?
      if (className == null) {
        throw GradleException(
          "Usage: $name -PclassName=com.github.benmanes.caffeine.cache.[CLASS_NAME]")
      } else if (!className.startsWith("com")) {
        className = "com.github.benmanes.caffeine.cache.$className"
      }
      args(name, className)
    }
  }
}
