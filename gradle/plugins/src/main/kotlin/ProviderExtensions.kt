import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.io.File

fun Provider<out FileSystemLocation>.absolutePath(): Provider<String> =
  map { it.asFile.absolutePath }

fun Provider<out FileSystemLocation>.relativePathFrom(relativeDir: File): Provider<String> =
  map { it.asFile.relativeTo(relativeDir).path }
