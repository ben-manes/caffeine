---
paths:
  - "**/src/*/java/**"
---

# Java Style

[Google Java Style](https://google.github.io/styleguide/javaguide.html), with the house conventions
below. They are consistent across the tree and deliberate, so do not convert code toward the
guide's defaults or toward google-java-format, which formats only JavaPoet's generated output.
Where the code is mixed, match the surrounding file.

## House conventions

- No blank line between the license header and `package`.
- Imports in blank-line-separated groups: static, `java`, `javax`, `org`, `com`, then the rest,
  each in ASCII order (`ij_java_imports_layout` in `.editorconfig`).
- Javadoc paragraphs: `<p>` alone on its own line directly after the previous paragraph (no blank
  `*` line), with the text starting on the next line.
- Wrapped block-tag text is indented at least four spaces past the `@`; most files align it with
  the text after the tag name.
- Nested classes made of small overrides (policy and collection views, iterators, sentinels, test
  doubles) may omit blank lines between members and keep one-line bodies.
- `@Override` may share the signature line. In tests and benchmarks several annotations may share
  a line (`@ParameterizedTest @MethodSource("…")`, `@Benchmark @Threads(1)`).
- Type-use annotations stay inline (`@Nullable Node<K, V> next;`), as does `@Var` on locals.
- A single-statement lambda block may stay on one line (`k -> { throw new AssertionError(); }`),
  and a line may break after a lambda arrow when one expression follows.
- Array initializers use `{x}` or `{ x }` as the file does; annotation arrays are `({"a", "b"})`.
- Package-private implementations may share their interface's file, and overloads may be split
  (ErrorProne's `MultipleTopLevelClasses` and `UngroupedOverloads` are disabled).

## Guide rules that changes most often break

- 100 columns, counted in code points, comments included.
- A continuation line is indented at least +4 from the line it continues, including a lambda body
  or switch rule that starts on the next line and the arguments of an annotation.
- Break before a binary operator, `.`, or `::`, and after a comma.
- One statement per line, single spaces between tokens (column alignment aside), and imports in the
  order above.
