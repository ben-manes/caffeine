import org.gradle.api.Task
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

/** Applies the standard doclet options. */
fun Javadoc.javadocOptions(block: StandardJavadocDocletOptions.() -> Unit) {
  (options as StandardJavadocDocletOptions).apply(block)
}

fun Task.incompatibleWithConfigurationCache() {
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}
