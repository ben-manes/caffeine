package com.github.benmanes.caffeine.examples.writebehind;

import java.util.Objects;

class ImmutablePair<K,V> {
    private final K key;
    private final V value;

    ImmutablePair(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ImmutablePair that = (ImmutablePair) o;
        return key == that.key &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
