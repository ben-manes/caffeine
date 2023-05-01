/** Java microbenchmark harness: https://github.com/melix/jmh-gradle-plugin */
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry
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
  exclude(module = "slf4j-test")
}

dependencies {
  jmh(libs.bundles.slf4j.nop)
}

jmh {
  jmhVersion.set(libs.versions.jmh.core)

  benchmarkMode.add("thrpt")
  warmupIterations.set(3)
  iterations.set(3)
  timeUnit.set("s")

  failOnError.set(true)
  forceGC.set(true)
  fork.set(1)

  resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
  resultFormat.set("json")

  val jvmArguments = mutableListOf("-Xmx2G")
  if (System.getenv("GRAALVM") == "true") {
    jvmArguments.addAll(listOf(
      "-XX:+UnlockExperimentalVMOptions", "-Dgraal.ShowConfiguration=info",
      "-XX:+EnableJVMCI", "-XX:+UseJVMCICompiler", "-XX:+EagerJVMCI"))
  }
  jvmArgs.set(jvmArguments)

  val includePattern: String? by project
  if (includePattern != null) {
    includes.set(listOf(includePattern))
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
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
  outputs.upToDateWhen { false }

  doFirst {
    if (!project.hasProperty("includePattern")) {
      throw GradleException("jmh: includePattern expected")
    }
  }
  finalizedBy(tasks.named("jmhReport"))
}

tasks.withType<JmhBytecodeGeneratorTask>().configureEach {
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(java.toolchain.languageVersion)
  })
}

tasks.named("jmhJar").configure {
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}

tasks.named("jmhReport").configure {
  notCompatibleWithConfigurationCache(
    "The $name task is not compatible with the configuration cache")
}

idea.module {
  scopes["PROVIDED"]!!["plus"]!!.add(configurations.jmh.get())
}

eclipse.classpath.file.whenMerged {
  if (this is Classpath) {
    entries.filterIsInstance<AbstractClasspathEntry>()
      .filter { it.path == "src/jmh/java" }
      .forEach { it.entryAttributes["test"] = "true" }
    entries.removeIf { (it is Library) && (it.moduleVersion?.name == "slf4j-nop") }
  }
}
