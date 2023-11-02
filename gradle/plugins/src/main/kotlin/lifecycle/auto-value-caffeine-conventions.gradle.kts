plugins {
  `java-library`
  id("com.diffplug.eclipse.apt")
}

java {
  withSourcesJar()
}

dependencies {
  compileOnly(libs.auto.value.annotations)
  annotationProcessor(libs.auto.value.processor)
}

tasks.named<Jar>("sourcesJar").configure {
  dependsOn(tasks.compileJava)
}
