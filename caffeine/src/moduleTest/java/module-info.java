import org.jspecify.annotations.NullMarked;

/** Module descriptor for blackbox integration testing of the Caffeine module. */
@NullMarked
@SuppressWarnings("PMD.DanglingJavadoc")
open module com.github.benmanes.caffeine.module {
  requires com.github.benmanes.caffeine;
  requires org.junit.jupiter.api;
  requires com.google.common;

  requires static com.google.errorprone.annotations;
  requires static org.jspecify;
}
