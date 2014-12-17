/**
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
package com.github.benmanes.caffeine.generator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * A generator, whose sequence is the lines of a file.
 *
 * @see https://github.com/brianfrankcooper/YCSB
 */
public class FileGenerator extends Generator
{
	String filename;
	String current;
	BufferedReader reader;

	/**
	 * Create a FileGenerator with the given file.
	 * @param _filename The file to read lines from.
	 */
	public FileGenerator(String _filename)
	{
		try {
			filename = _filename;
			File file = new File(filename);
			FileInputStream in = new FileInputStream(file);
			reader = new BufferedReader(new InputStreamReader(in));
		} catch(IOException e) {
			System.err.println("Exception: " + e);
		}
	}

	/**
	 * Return the next string of the sequence, ie the next line of the file.
	 */
	@Override
  public synchronized String nextString()
	{
		try {
			return current = reader.readLine();
		} catch(NullPointerException e) {
			System.err.println("NullPointerException: " + filename + ':' + current);
			throw e;
		} catch(IOException e) {
			System.err.println("Exception: " + e);
			return null;
		}
	}

	/**
	 * Return the previous read line.
	 */
	@Override
  public String lastString()
	{
		return current;
	}

	/**
	 * Reopen the file to reuse values.
	 */
	public synchronized void reloadFile()
	{
		try {
			System.err.println("Reload " + filename);
			reader.close();
			File file = new File(filename);
			FileInputStream in = new FileInputStream(file);
			reader = new BufferedReader(new InputStreamReader(in));
		} catch(IOException e) {
			System.err.println("Exception: " + e);
		}
	}
}
