/** This module contains in-memory caching functionality. */
module com.github.benmanes.caffeine {
  exports com.github.benmanes.caffeine.cache;
  exports com.github.benmanes.caffeine.cache.stats;

  requires static com.google.errorprone.annotations;
  requires static org.checkerframework.checker.qual;
}
