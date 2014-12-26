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
package com.github.benmanes.caffeine.atomic;

import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class PaddedAtomicTest {

  @Test
  public void create_boolean() {
    new PaddedAtomicBoolean();
    new PaddedAtomicBoolean(true);
  }

  @Test
  public void create_int() {
    new PaddedAtomicInteger();
    new PaddedAtomicInteger(1);
  }

  @Test
  public void create_long() {
    new PaddedAtomicInteger();
    new PaddedAtomicInteger(1);
  }

  @Test
  public void create_reference() {
    new PaddedAtomicReference<Void>();
    new PaddedAtomicReference<Void>(null);
  }
}
