plugins {
  `jvm-ecosystem`
}

val ecj: Configuration by configurations.creating

dependencies {
  ecj(libs.ecj)
}

sourceSets.configureEach {
  val compileTask = tasks.named<JavaCompile>(compileJavaTaskName)
  tasks.register<EclipseJavaCompile>(getTaskName("ecj", /* target= */ null)) {
    description = "Run ECJ analysis for ${this@configureEach.name} classes"
    properties = rootProject.layout.projectDirectory.file(
      "gradle/config/eclipse/org.eclipse.jdt.core.prefs")
    compileClasspath = compileTask.map { it.classpath }
    javaSources = allJava.asFileTree
    dependsOn(compileTask)
    classpath = ecj
  }
}

abstract class EclipseJavaCompile : JavaExec() {
  @InputFiles lateinit var compileClasspath: Provider<FileCollection>
  @InputFiles lateinit var javaSources: FileCollection
  @InputFile lateinit var properties: RegularFile

  init {
    group = "ECJ"
    mainClass = "org.eclipse.jdt.internal.compiler.batch.Main"
  }

  @TaskAction
  override fun exec() {
    val sources = javaSources.filter { it.name != "module-info.java" }.map { it.absolutePath }
    args("-classpath", compileClasspath.get().filter { it.exists() }.asPath)
    args("-properties", properties.asFile.absolutePath)
    args("-encoding", "UTF-8")
    args("-enableJavadoc")
    args("-failOnWarning")
    args("--release", 23)
    args("-proc:none")
    args("-d", "none")
    args(sources)
    super.exec()
  }
}
