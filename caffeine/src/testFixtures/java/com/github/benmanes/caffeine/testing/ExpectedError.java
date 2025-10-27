/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.testing;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.DoNotExtendJavaLangError")
public final class ExpectedError extends Error {
  public static final ExpectedError INSTANCE = new ExpectedError();
  public static final StackOverflowError STACK_OVERFLOW = new StackOverflowError();

  private static final long serialVersionUID = 1L;

  private ExpectedError() {
    super(/* message= */ null, /* cause= */ null,
        /* enableSuppression= */ false, /* writableStackTrace= */ false);
  }
}
