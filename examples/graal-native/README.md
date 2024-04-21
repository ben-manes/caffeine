[Graal native image][] uses ahead-of-time compilation to build an optimized executable.

### Install GraalVM using sdkman.io

```console
sdk install java 22-graal
sdk use java 22-graal
export JAVA_HOME=${SDKMAN_DIR}/candidates/java/current
```

### Run on the JVM with an agent to capture class metadata

```console
./gradlew run -Pagent
```

### Copy the metadata as the source configuration

```console
./gradlew metadataCopy --task run --dir src/main/resources/META-INF/native-image
```

### Compile and run the native binary

```console
./gradlew nativeRun
```

### Try the binary yourself

```console
./build/native/nativeCompile/graal-native
```

### Run the tests against the native binary

```console
./gradlew nativeTest
```

[Graal native image]: https://www.graalvm.org/22.0/reference-manual/native-image
