@file:Suppress("PackageDirectoryMismatch")
import com.autonomousapps.tasks.FindDeclaredProcsTask
import com.autonomousapps.tasks.ResolveExternalDependenciesTask
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  id("errorprone.caffeine")
  id("com.autonomousapps.dependency-analysis")
}

val downloadCaffeine = tasks.named("downloadCaffeine")

tasks.withType<FindDeclaredProcsTask>().configureEach {
  inputs.files(downloadCaffeine.map { it.outputs.files }).withPathSensitivity(RELATIVE)
}

tasks.withType<ResolveExternalDependenciesTask>().configureEach {
  dependsOn(gradle.includedBuild("plugins").task(":resolveExternalDependencies"))
}
