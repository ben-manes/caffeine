import org.gradle.api.Task
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.systemProperties

/** Applies the standard doclet options. */
fun Javadoc.javadocOptions(block: StandardJavadocDocletOptions.() -> Unit) {
  (options as StandardJavadocDocletOptions).apply(block)
}

fun Task.incompatibleWithConfigurationCache() {
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}

fun Test.useParallelJUnitJupiter() {
  val threadCount = Runtime.getRuntime().availableProcessors()
  systemProperties(
    "testng.parallel" to "methods",
    "testng.threadCount" to threadCount.toString(),
    "junit.jupiter.execution.parallel.enabled" to "true",
    "junit.jupiter.execution.parallel.mode.default" to "concurrent",
    "junit.jupiter.execution.parallel.mode.classes.default" to "concurrent")
}
