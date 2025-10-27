import org.jspecify.annotations.NullMarked;

/** Module descriptor for blackbox integration testing of the Guava adapter module. */
@NullMarked
@SuppressWarnings("PMD.DanglingJavadoc")
open module com.github.benmanes.caffeine.guava.module {
  requires com.github.benmanes.caffeine.guava;
  requires org.junit.jupiter.api;

  requires static com.google.errorprone.annotations;
  requires static org.jspecify;
}
