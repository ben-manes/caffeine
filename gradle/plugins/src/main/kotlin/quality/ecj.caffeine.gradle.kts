plugins {
  `jvm-ecosystem`
}

val ecj by configurations.registering

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
    inputs.files(compileTask.map { it.outputs.files })
    javaSources = allJava.asFileTree
    classpath(ecj)
  }
}

abstract class EclipseJavaCompile : JavaExec() {
  @get:InputFiles
  abstract val compileClasspath: Property<FileCollection>
  @get:InputFiles
  abstract val javaSources: ConfigurableFileCollection
  @get:InputFile
  abstract val properties: RegularFileProperty

  init {
    group = "ECJ"
    mainClass = "org.eclipse.jdt.internal.compiler.batch.Main"
  }

  @TaskAction
  override fun exec() {
    val sources = javaSources.filter { it.name != "module-info.java" }.map { it.absolutePath }
    args("-classpath", compileClasspath.get().filter { it.exists() }.asPath)
    args("-properties", properties.get().asFile.absolutePath)
    args("-encoding", "UTF-8")
    args("-enableJavadoc")
    args("-failOnWarning")
    args("--release", 24)
    args("-proc:none")
    args("-d", "none")
    args(sources)
    super.exec()
  }
}
