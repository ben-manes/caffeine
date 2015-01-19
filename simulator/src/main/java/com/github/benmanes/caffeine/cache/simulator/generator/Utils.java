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

import java.util.Random;

/**
 * Utility functions.
 *
 * https://github.com/brianfrankcooper/YCSB
 */
class Utils
{
  private static final Random rand = new Random();
  private static final ThreadLocal<Random> rng = new ThreadLocal<Random>();

  public static Random random() {
    Random ret = rng.get();
    if(ret == null) {
      ret = new Random(rand.nextLong());
      rng.set(ret);
    }
    return ret;
  }
      /**
       * Generate a random ASCII string of a given length.
       */
      public static String ASCIIString(int length)
      {
   int interval='~'-' '+1;

        byte []buf = new byte[length];
        random().nextBytes(buf);
        for (int i = 0; i < length; i++) {
          if (buf[i] < 0) {
            buf[i] = (byte)((-buf[i] % interval) + ' ');
          } else {
            buf[i] = (byte)((buf[i] % interval) + ' ');
          }
        }
        return new String(buf);
      }

      /**
       * Hash an integer value.
       */
      public static long hash(long val)
      {
   return FNVhash64(val);
      }

      public static final int FNV_offset_basis_32=0x811c9dc5;
      public static final int FNV_prime_32=16777619;

      /**
       * 32 bit FNV hash. Produces more "random" hashes than (say) String.hashCode().
       *
       * @param val The value to hash.
       * @return The hash value
       */
      public static int FNVhash32(int val)
      {
   //from http://en.wikipedia.org/wiki/Fowler_Noll_Vo_hash
   int hashval = FNV_offset_basis_32;

   for (int i=0; i<4; i++)
   {
      int octet=val&0x00ff;
      val=val>>8;

      hashval = hashval ^ octet;
      hashval = hashval * FNV_prime_32;
      //hashval = hashval ^ octet;
   }
   return Math.abs(hashval);
      }

      public static final long FNV_offset_basis_64=0xCBF29CE484222325L;
      public static final long FNV_prime_64=1099511628211L;

      /**
       * 64 bit FNV hash. Produces more "random" hashes than (say) String.hashCode().
       *
       * @param val The value to hash.
       * @return The hash value
       */
      public static long FNVhash64(long val)
      {
   //from http://en.wikipedia.org/wiki/Fowler_Noll_Vo_hash
   long hashval = FNV_offset_basis_64;

   for (int i=0; i<8; i++)
   {
      long octet=val&0x00ff;
      val=val>>8;

      hashval = hashval ^ octet;
      hashval = hashval * FNV_prime_64;
      //hashval = hashval ^ octet;
   }
   return Math.abs(hashval);
      }
}
