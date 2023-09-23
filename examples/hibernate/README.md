[Hibernate][] can be configured to use a [second-level cache] to reduce the number of accesses
to the database by caching data in memory to be shared between sessions.

In [hibernate.conf](src/main/resources/hibernate.properties) specify the JCache provider and enable
the second-level cache.

```ini
hibernate.cache.use_second_level_cache=true
hibernate.cache.region.factory_class=jcache
hibernate.javax.cache.provider=com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider
```

The caches are configured in Caffeine's [application.conf](src/main/resources/application.conf)
following the [reference format](../../jcache/src/main/resources/reference.conf). The default file
path may be overridden by setting the `hibernate.javax.cache.uri` in the previous step.

```hocon
caffeine.jcache {
  default {
    monitoring.statistics = true
  }

  # Hibernate framework caches
  default-query-results-region {}
  default-update-timestamps-region {}

  # Hibernate application caches
  com.github.benmanes.caffeine.examples.hibernate.User {}
}
```

Enable caching on the entity.

```java
@Entity
@Cache(usage = READ_WRITE)
public class User { ... }
```

Hibernate will then manage the cache to transparently avoid database calls.

```java
// miss on first access
sessionFactory.fromSession(session -> session.get(User.class, id));
assertThat(sessionFactory.getStatistics().getSecondLevelCacheMissCount()).isEqualTo(1);

// hit on lookup
sessionFactory.fromSession(session -> session.get(User.class, id));
assertThat(sessionFactory.getStatistics().getSecondLevelCacheHitCount()).isEqualTo(1);
```

[Hibernate]: https://hibernate.org/orm/
[second-level cache]: https://docs.jboss.org/hibernate/orm/6.3/introduction/html_single/Hibernate_Introduction.html#second-level-cache-configuration
