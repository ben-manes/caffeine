import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.KotlinClosure2
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

fun Test.failOnSkippedTests() {
  testLogging.events(SKIPPED)
  afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
    if (result.resultType == TestResult.ResultType.SKIPPED) {
      throw GradleException("Do not skip tests (e.g. ${descriptor.name})")
    }
  }))
}
