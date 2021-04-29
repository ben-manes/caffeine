module com.github.benmanes.caffeine {
  exports com.github.benmanes.caffeine.cache;
  exports com.github.benmanes.caffeine.cache.stats;

  requires static transitive com.google.errorprone.annotations;
  requires static transitive org.checkerframework.checker.qual;
}
