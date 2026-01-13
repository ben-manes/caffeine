/*
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

import static org.junit.Assert.assertNull;

import org.junit.Test;

@SuppressWarnings("IdentifierName")
public class NBHMReplaceTest {

  @Test
  public void replaceOnEmptyUnboundedMap() {
    assertNull(Caffeine.newBuilder().build().asMap().replace("k", "v"));
  }

  @Test
  public void replaceOnEmptyBoundedMap() {
    assertNull(Caffeine.newBuilder().maximumSize(100).build().asMap().replace("k", "v"));
  }
}
