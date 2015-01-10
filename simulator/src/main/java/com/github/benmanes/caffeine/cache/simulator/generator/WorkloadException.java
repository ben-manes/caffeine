/*
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.github.benmanes.caffeine.cache.simulator.generator;

/**
 * The workload tried to do something bad.
 *
 * @see https://github.com/brianfrankcooper/YCSB
 */
public class WorkloadException extends Exception
{
  /**
   *
   */
  private static final long serialVersionUID = 8844396756042772132L;

  public WorkloadException(String message)
  {
    super(message);
  }

  public WorkloadException()
  {
    super();
  }

  public WorkloadException(String message, Throwable cause)
  {
    super(message,cause);
  }

  public WorkloadException(Throwable cause)
  {
    super(cause);
  }
}
