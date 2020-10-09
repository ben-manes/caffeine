/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.io.InputStream;

import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JandexTest {

  @Test
  public void loadIndex() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/META-INF/jandex.idx")) {
      Index index = new IndexReader(input).read();
      assertThat(index.getKnownClasses(), is(not(empty())));
    }
  }
}
