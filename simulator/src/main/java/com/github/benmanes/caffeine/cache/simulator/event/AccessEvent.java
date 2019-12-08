package com.github.benmanes.caffeine.cache.simulator.event;

public class AccessEvent {
  private final long key;

  public AccessEvent(long key) {
    this.key = key;
  }

  public long getKey() {
    return key;
  }
}
