package com.github.benmanes.caffeine.cache.simulator.policy.esp;

public interface CommonNode {
  long getAccessTime();
  void setAccessTime(long accessTime);

  int getFrequency();
  void setFrequency(int frequency);

  long getInsertionTime();
  void setInsertionTime(long insertionTime);

  Status getStatus();
  void setStatus(Status status);

  int getLevel();
  void setLevel(int level);

  int Shahaf =0;


  enum Status {
    // Define possible status values, e.g., WINDOW, MAIN, etc.
  }
}
