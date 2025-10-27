import org.gradle.api.Task
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.systemProperties
import org.gradle.external.javadoc.StandardJavadocDocletOptions

/** Applies the standard doclet options. */
fun Javadoc.javadocOptions(block: StandardJavadocDocletOptions.() -> Unit) {
  (options as StandardJavadocDocletOptions).apply(block)
}

fun Task.incompatibleWithConfigurationCache() {
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}

fun Test.useParallelJUnitJupiter() {
  systemProperties(
    "junit.jupiter.execution.parallel.enabled" to "true",
    "junit.jupiter.execution.parallel.mode.default" to "concurrent")
}
