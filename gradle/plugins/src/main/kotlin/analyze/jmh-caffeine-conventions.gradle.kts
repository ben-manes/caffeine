/** Java microbenchmark harness: https://github.com/melix/jmh-gradle-plugin */
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.Library
import me.champeau.jmh.JmhBytecodeGeneratorTask
import me.champeau.jmh.JMHTask as JmhTask

plugins {
  idea
  eclipse
  `java-library`
  id("me.champeau.jmh")
  id("io.morethan.jmhreport")
}

configurations.jmh {
  extendsFrom(configurations.testImplementation.get())
  exclude(module = "jazzer-junit")
  exclude(module = "slf4j-test")
}

dependencies {
  jmh(libs.bundles.slf4j.nop)
}

jmh {
  jmhVersion = libs.versions.jmh.core

  benchmarkMode.add("thrpt")
  warmupIterations = 3
  iterations = 3
  timeUnit = "s"

  failOnError = true
  forceGC = true
  fork = 1

  resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
  resultFormat = "json"

  val jvmArguments = mutableListOf("-Xmx2G")
  if (System.getenv("GRAALVM") == "true") {
    jvmArguments += listOf(
      "-XX:+UnlockExperimentalVMOptions", "-Dgraal.ShowConfiguration=info",
      "-XX:+EnableJVMCI", "-XX:+UseJVMCICompiler", "-XX:+EagerJVMCI")
  }
  jvmArgs = jvmArguments

  val includePattern: String? by project
  if (includePattern != null) {
    includes = listOf(includePattern)
  }

  // Benchmark parameters: Separated by '&' for parameter types, and ',' for multiple values
  val params = findProperty("benchmarkParameters") as String?
  if (params != null) {
    for (token in params.split("&")) {
      val param = token.split("=")
      require(param.size == 2) { "Expected two parts but was ${param.size} in $token" }
      benchmarkParameters.put(param[0], objects.listProperty<String>().value(param[1].split(",")))
    }
  }
}

jmhReport {
  jmhResultPath = file(jmh.resultsFile).toString()
  jmhReportOutput = file(layout.buildDirectory.file("reports/jmh")).toString()
}

tasks.withType<JmhTask>().configureEach {
  group = "Benchmarks"
  description = "Executes a Java microbenchmark"
  incompatibleWithConfigurationCache()
  outputs.upToDateWhen { false }

  doFirst {
    if (!project.hasProperty("includePattern")) {
      throw GradleException("jmh: includePattern expected")
    }
  }
  finalizedBy(tasks.named("jmhReport"))
}

tasks.withType<JmhBytecodeGeneratorTask>().configureEach {
  javaLauncher = javaToolchains.launcherFor {
    languageVersion = java.toolchain.languageVersion
  }
}

tasks.named("jmhJar").configure {
  incompatibleWithConfigurationCache()
}

tasks.named("jmhReport").configure {
  incompatibleWithConfigurationCache()
}

idea.module {
  scopes["PROVIDED"]!!["plus"]!!.add(configurations.jmh.get())
}

eclipse.classpath.file.whenMerged {
  if (this is Classpath) {
    entries.removeIf { (it is Library) && (it.moduleVersion?.name == "slf4j-nop") }
  }
}
