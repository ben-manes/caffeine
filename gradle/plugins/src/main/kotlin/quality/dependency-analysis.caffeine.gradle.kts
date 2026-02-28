@file:Suppress("PackageDirectoryMismatch")
import com.autonomousapps.tasks.FindDeclaredProcsTask
import com.autonomousapps.tasks.ResolveExternalDependenciesTask

plugins {
  id("errorprone.caffeine")
  id("com.autonomousapps.dependency-analysis")
}

val downloadCaffeine by tasks.existing

tasks.withType<FindDeclaredProcsTask>().configureEach {
  inputs.files(downloadCaffeine.map { it.outputs.files })
}

tasks.withType<ResolveExternalDependenciesTask>().configureEach {
  dependsOn(gradle.includedBuild("plugins").task(":resolveExternalDependencies"))
}
