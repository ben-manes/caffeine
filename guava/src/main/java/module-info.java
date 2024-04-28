/** This module contains an adapter to the Guava cache interfaces. */
@SuppressWarnings("requires-transitive-automatic")
module com.github.benmanes.caffeine.guava {
  requires transitive com.github.benmanes.caffeine;
  requires transitive com.google.common;

  requires static com.google.errorprone.annotations;
  requires static org.checkerframework.checker.qual;
}
