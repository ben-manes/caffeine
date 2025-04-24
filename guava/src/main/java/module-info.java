import org.jspecify.annotations.NullMarked;

/** This module contains an adapter to the Guava cache interfaces. */
@NullMarked
module com.github.benmanes.caffeine.guava {
  requires transitive com.github.benmanes.caffeine;
  requires transitive com.google.common;

  requires static com.google.errorprone.annotations;
  requires static org.jspecify;
}
