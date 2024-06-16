plugins {
  `java-library`
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
