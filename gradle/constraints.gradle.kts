project.extra.set("restrictions", listOf(
  libs("constraints-jcommander"),
  libs("constraints-protobuf"),
  libs("constraints-xstream"),
  libs("constraints-nekohtml"),
  libs("constraints-bcel"),
  libs("constraints-commons-text"),
  libs("constraints-httpclient"),
  libs("constraints-bouncycastle"),
  libs("constraints-jsoup"),
  libs("constraints-snakeyaml"),
  libs("constraints-xerces")))

fun libs(name: String): MinimalExternalModuleDependency =
  the<VersionCatalogsExtension>().named("libs").findLibrary(name).orElseThrow().get()
