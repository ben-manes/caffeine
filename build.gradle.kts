plugins {
  id("base.caffeine")
  id("coverage.caffeine")
  id("intellij.caffeine")
  id("versions.caffeine")
  id("sonarqube.caffeine")
  id("dependency-check.caffeine")
  alias(libs.plugins.nmcp.aggregation)
  alias(libs.plugins.dependency.analysis)
}

dependencies {
  subprojects { nmcpAggregation(project(path)) }
}

nmcpAggregation {
  allowDuplicateProjectNames = true
  publishAllChecksums = true

  centralPortal {
    username = providers.gradleProperty("centralPortalUsername")
    password = providers.gradleProperty("centralPortalPassword")
  }
}
