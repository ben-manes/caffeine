@file:Suppress("PackageDirectoryMismatch")
import org.revapi.gradle.RevapiAcceptAllBreaksTask
import org.revapi.gradle.RevapiAcceptBreakTask
import org.revapi.gradle.RevapiAnalyzeTask
import org.revapi.gradle.RevapiReportTask
import org.revapi.gradle.RevapiVersionOverrideTask

plugins {
  `java-library`
  id("org.revapi.revapi-gradle-plugin")
}

revapi {
  setOldVersion(libs.caffeine.get().version)
}

val revapiTasks = listOf(
  RevapiAcceptAllBreaksTask::class,
  RevapiVersionOverrideTask::class,
  RevapiAcceptBreakTask::class,
  RevapiAnalyzeTask::class,
  RevapiReportTask::class
)

revapiTasks.forEach { taskClass ->
  tasks.withType(taskClass.java).configureEach {
    enabled = providers.gradleProperty("revapi").isPresent
    if (enabled) {
      incompatibleWithConfigurationCache()
    }
  }
}
