In some scenarios, it can be useful to associate a single cache value with alternative keys. Similar
to a relational database, the cache acts as a table with a primary key, where the value is a row of
data, and unique hash indexes allow for fast retrieval using secondary keys. When the value is
updated or deleted, either explicitly or by eviction, the changes should be reflected in the key
associations. An _Indexable Cache_ provides a straightforward solution for achieving multiple unique
key lookups to a single value.

### A simple example
In the schema below, the application needs to find a user by the row id for direct queries, by the
username during login, and by the email during a password recovery flow.

```sql
CREATE TABLE user_info (
  id            bigserial primary key,
  first_name    varchar(255) NOT NULL,
  last_name     varchar(255) NOT NULL,
  email         varchar(255) NOT NULL,
  username      varchar(255) NOT NULL,
  password_hash varchar(255) NOT NULL,
  created_on    timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
  modified_on   timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX user_info_email_idx ON user_info (email);
CREATE UNIQUE INDEX user_info_username_idx ON user_info (username);
```

Java's [Data-Oriented Programming][] approach represents each lookup key as a distinct type and uses
pattern matching when loading an entry on a cache miss.

```java
sealed interface UserKey permits UserById, UserByLogin, UserByEmail {
  record UserByLogin(String login) implements UserKey {}
  record UserByEmail(String email) implements UserKey {}
  record UserById(long id) implements UserKey {}
}

private User findUser(UserKey key) {
  Condition condition = switch (key) {
    case UserById(var id) -> USER_INFO.ID.eq(id);
    case UserByEmail(var email) -> USER_INFO.EMAIL.eq(email);
    case UserByLogin(var login) -> USER_INFO.USERNAME.eq(login);
  };
  return db.selectFrom(USER_INFO).where(condition).fetchOneInto(User.class);
}
```

The cache is constructed with functions to build the indexes, the data loader, and the bounding
constraints. The value can then be queried using the typed key.

```java
var cache = new IndexedCache.Builder<UserKey, User>()
    .primaryKey(user -> new UserById(user.id()))
    .addSecondaryKey(user -> new UserByEmail(user.email()))
    .addSecondaryKey(user -> new UserByLogin(user.username()))
    .expireAfterWrite(Duration.ofMinutes(5))
    .maximumSize(10_000)
    .build(this::findUser);

var userByEmail = cache.get(new UserByEmail("john.doe@example.com"));
var userByLogin = cache.get(new UserByLogin("john.doe"));
assertThat(userByEmail).isSameInstanceAs(userByLogin);
```

### How it works
The sample [IndexedCache][] combines a key-value cache with an associated mapping from the
individual keys to the entry's complete set. Consistency is maintained by acquiring the entry's lock
through the cache using the primary key before updating the index. This prevents race conditions
when the entry is concurrently updated and evicted, which could otherwise lead to missing or
non-resident key associations in the index. On eviction, a listener discards the keys while holding
the cache's entry lock.

When a value is not found and must be loaded, an important performance optimization is to avoid a
cache stampede of redundant queries by performing that work once for all callers. This is
challenging when there are alternative lookup keys, as the cache is unaware of a canonical key to
lock against until the value is loaded. While using a single shared lock across all keys could solve
this, it would also penalize distinct entries by the slow loading time of preceding calls. A
[StripedLock][] provides a balanced solution by memoizing per key and using a last-write-wins policy
if the same value is loaded by concurrent calls using different keys.

[Data-Oriented Programming]: https://inside.java/2024/05/23/dop-v1-1-introduction
[IndexedCache]: src/main/java/com/github/benmanes/caffeine/examples/indexable/IndexedCache.java
[StripedLock]: https://guava.dev/releases/snapshot-jre/api/docs/com/google/common/util/concurrent/Striped.html
