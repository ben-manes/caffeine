/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * A non-reentrant mutual exclusion {@link Lock}. This type of lock does not allow recursive locks
 * held by the same thread and will deadlock if used recursively. This type of lock is useful when
 * reentrancy is not required and a slim lock is desired.
 * <p>
 * A {@code NonReentrantLock} is <em>owned</em> by the thread last successfully locking, but not yet
 * unlocking it. A thread invoking {@code lock} will return, successfully acquiring the lock, when
 * the lock is not owned by another thread. This can be checked using methods
 * {@link #isHeldByCurrentThread}.
 * <p>
 * It is recommended practice to <em>always</em> immediately follow a call to {@code lock} with a
 * {@code try} block, most typically in a before/after construction such as:
 *
 * <pre>
 * {@code
 * class X {
 *   private final ReentrantLock lock = new ReentrantLock();
 *   // ...
 *
 *   public void m() {
 *     lock.lock();  // block until condition holds
 *     try {
 *       // ... method body
 *     } finally {
 *       lock.unlock()
 *     }
 *   }
 * }}
 * </pre>
 * <p>
 * In addition to implementing the {@link Lock} interface, this class defines a number of
 * {@code public} and {@code protected} methods for inspecting the state of the lock. Some of these
 * methods are only useful for instrumentation and monitoring.
 * <p>
 * Serialization of this class behaves in the same way as built-in locks: a deserialized lock is in
 * the unlocked state, regardless of its state when serialized.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class NonReentrantLock implements Lock, Serializable {
  static final long serialVersionUID = 1L;

  /** Synchronizer providing all implementation mechanics */
  final Sync sync;

  public NonReentrantLock() {
    this.sync = new Sync();
  }

  @Override
  public void lock() {
    sync.lock();
  }

  @Override
  public void lockInterruptibly() throws InterruptedException {
    sync.lockInterruptibly();
  }

  @Override
  public boolean tryLock() {
    return sync.tryLock();
  }

  @Override
  public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    return sync.tryLock(time, unit);
  }

  @Override
  public void unlock() {
    sync.unlock();
  }

  @Override
  public Condition newCondition() {
    return sync.newCondition();
  }

  /**
   * Queries if this lock is held by the current thread.
   * <p>
   * Analogous to the {@link Thread#holdsLock(Object)} method for built-in monitor locks, this
   * method is typically used for debugging and testing. For example, a method that should only be
   * called while a lock is held can assert that this is the case:
   * <pre>
   * {
   *   &#064;code
   *   class X {
   *     ReentrantLock lock = new ReentrantLock();
   *
   *     // ...
   *
   *     public void m() {
   *       assert lock.isHeldByCurrentThread();
   *       // ... method body
   *     }
   *   }
   * }
   * </pre>
   * <p>
   * It can also be used to ensure that a reentrant lock is used in a non-reentrant manner, for
   * example:
   *
   * <pre>
   * {
   *   &#064;code
   *   class X {
   *     ReentrantLock lock = new ReentrantLock();
   *
   *     // ...
   *
   *     public void m() {
   *       assert !lock.isHeldByCurrentThread();
   *       lock.lock();
   *       try {
   *         // ... method body
   *       } finally {
   *         lock.unlock();
   *       }
   *     }
   *   }
   * }
   * </pre>
   *
   * @return {@code true} if current thread holds this lock and {@code false} otherwise
   */
  public boolean isHeldByCurrentThread() {
    return sync.isHeldExclusively();
  }

  /**
   * Queries if this lock is held by any thread. This method is designed for use in monitoring of
   * the system state, not for synchronization control.
   *
   * @return {@code true} if any thread holds this lock and {@code false} otherwise
   */
  public boolean isLocked() {
      return sync.isLocked();
  }

  /**
   * Returns the thread that currently owns this lock, or {@code null} if not owned. When this
   * method is called by a thread that is not the owner, the return value reflects a best-effort
   * approximation of current lock status. For example, the owner may be momentarily {@code null}
   * even if there are threads trying to acquire the lock but have not yet done so. This method is
   * designed to facilitate construction of subclasses that provide more extensive lock monitoring
   * facilities.
   *
   * @return the owner, or {@code null} if not owned
   */
  public Thread getOwner() {
    return sync.getOwner();
  }

  /**
   * Queries whether any threads are waiting to acquire this lock. Note that because cancellations
   * may occur at any time, a {@code true} return does not guarantee that any other thread will ever
   * acquire this lock. This method is designed primarily for use in monitoring of the system state.
   *
   * @return {@code true} if there may be other threads waiting to acquire the lock
   */
  public boolean hasQueuedThreads() {
    return sync.hasQueuedThreads();
  }

  /**
   * Queries whether the given thread is waiting to acquire this lock. Note that because
   * cancellations may occur at any time, a {@code true} return does not guarantee that this thread
   * will ever acquire this lock. This method is designed primarily for use in monitoring of the
   * system state.
   *
   * @param thread the thread
   * @return {@code true} if the given thread is queued waiting for this lock
   * @throws NullPointerException if the thread is null
   */
  public boolean hasQueuedThread(Thread thread) {
    return sync.isQueued(thread);
  }

  /**
   * Returns an estimate of the number of threads waiting to acquire this lock. The value is only an
   * estimate because the number of threads may change dynamically while this method traverses
   * internal data structures. This method is designed for use in monitoring of the system state,
   * not for synchronization control.
   *
   * @return the estimated number of threads waiting for this lock
   */
  public int getQueueLength() {
    return sync.getQueueLength();
  }

  /**
   * Returns a collection containing threads that may be waiting to acquire this lock. Because the
   * actual set of threads may change dynamically while constructing this result, the returned
   * collection is only a best-effort estimate. The elements of the returned collection are in no
   * particular order. This method is designed to facilitate construction of subclasses that provide
   * more extensive monitoring facilities.
   *
   * @return the collection of threads
   */
  public Collection<Thread> getQueuedThreads() {
    return sync.getQueuedThreads();
  }

  /**
   * Queries whether any threads are waiting on the given condition associated with this lock. Note
   * that because timeouts and interrupts may occur at any time, a {@code true} return does not
   * guarantee that a future {@code signal} will awaken any threads. This method is designed
   * primarily for use in monitoring of the system state.
   *
   * @param condition the condition
   * @return {@code true} if there are any waiting threads
   * @throws IllegalMonitorStateException if this lock is not held
   * @throws IllegalArgumentException if the given condition is not associated with this lock
   * @throws NullPointerException if the condition is null
   */
  public boolean hasWaiters(Condition condition) {
    requireNonNull(condition);
    if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject)) {
      throw new IllegalArgumentException("not owner");
    }
    return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject) condition);
  }

  /**
   * Returns an estimate of the number of threads waiting on the given condition associated with
   * this lock. Note that because timeouts and interrupts may occur at any time, the estimate serves
   * only as an upper bound on the actual number of waiters. This method is designed for use in
   * monitoring of the system state, not for synchronization control.
   *
   * @param condition the condition
   * @return the estimated number of waiting threads
   * @throws IllegalMonitorStateException if this lock is not held
   * @throws IllegalArgumentException if the given condition is not associated with this lock
   * @throws NullPointerException if the condition is null
   */
  public int getWaitQueueLength(Condition condition) {
    requireNonNull(condition);
    if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject)) {
      throw new IllegalArgumentException("not owner");
    }
    return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject) condition);
  }

  /**
   * Returns a collection containing those threads that may be waiting on the given condition
   * associated with this lock. Because the actual set of threads may change dynamically while
   * constructing this result, the returned collection is only a best-effort estimate. The elements
   * of the returned collection are in no particular order. This method is designed to facilitate
   * construction of subclasses that provide more extensive condition monitoring facilities.
   *
   * @param condition the condition
   * @return the collection of threads
   * @throws IllegalMonitorStateException if this lock is not held
   * @throws IllegalArgumentException if the given condition is not associated with this lock
   * @throws NullPointerException if the condition is null
   */
  public Collection<Thread> getWaitingThreads(Condition condition) {
    requireNonNull(condition);
    if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject)) {
      throw new IllegalArgumentException("not owner");
    }
    return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject) condition);
  }

  /**
   * Returns a string identifying this lock, as well as its lock state. The state, in brackets,
   * includes either the String {@code "Unlocked"} or the String {@code "Locked by"} followed by the
   * {@linkplain Thread#getName name} of the owning thread.
   *
   * @return a string identifying this lock, as well as its lock state
   */
  @Override
  public String toString() {
    Thread o = sync.getOwner();
    String status = (o == null) ? "[Unlocked]" : "[Locked by thread " + o.getName() + "]";
    return super.toString() + status;
  }

  /** A non-fair lock using AQS state to represent if the lock is held. */
  static final class Sync extends AbstractQueuedSynchronizer implements Lock, Serializable {
    static final long serialVersionUID = 1L;
    static final int UNLOCKED = 0;
    static final int LOCKED = 1;

    @Override
    public void lock() {
      acquire(LOCKED);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      acquireInterruptibly(LOCKED);
    }

    @Override
    public boolean tryLock() {
      return tryAcquire(LOCKED);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      return tryAcquireNanos(1, unit.toNanos(time));
    }

    @Override
    public void unlock() {
      release(UNLOCKED);
    }

    @Override
    public Condition newCondition() {
      return new ConditionObject();
    }

    @Override
    protected boolean tryAcquire(int acquires) {
      if ((getState() == UNLOCKED) && compareAndSetState(UNLOCKED, LOCKED)) {
        setExclusiveOwnerThread(Thread.currentThread());
        return true;
      }
      return false;
    }

    @Override
    protected boolean tryRelease(int releases) {
      if (Thread.currentThread() != getExclusiveOwnerThread()) {
        throw new IllegalMonitorStateException();
      }
      setExclusiveOwnerThread(null);
      setState(UNLOCKED);
      return true;
    }

    @Override
    protected boolean isHeldExclusively() {
      return isLocked() && (getExclusiveOwnerThread() == Thread.currentThread());
    }

    public boolean isLocked() {
      return getState() == LOCKED;
    }

    public Thread getOwner() {
      return getExclusiveOwnerThread();
    }

    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
      s.defaultReadObject();
      setState(UNLOCKED);
    }
  }
}
