@file:Suppress("UnstableApiUsage")
import org.gradle.api.Action
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE
import org.gradle.api.attributes.Bundling.EXTERNAL
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE
import org.gradle.api.attributes.Category.LIBRARY
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.LibraryElements.JAR
import org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE

fun Configuration.configureAsRuntimeIncoming() {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes(RuntimeJarAttributes)
}

fun Configuration.configureAsRuntimeOutgoing() {
  isCanBeConsumed = true
  isCanBeResolved = false
  attributes(RuntimeJarAttributes)
}

private val RuntimeJarAttributes: Action<AttributeContainer> = Action {
  attribute(USAGE_ATTRIBUTE, named(Usage::class.java, JAVA_RUNTIME))
  attribute(CATEGORY_ATTRIBUTE, named(Category::class.java, LIBRARY))
  attribute(BUNDLING_ATTRIBUTE, named(Bundling::class.java, EXTERNAL))
  attribute(LIBRARY_ELEMENTS_ATTRIBUTE, named(LibraryElements::class.java, JAR))
}
