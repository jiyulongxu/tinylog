/*
 * Copyright 2017 Martin Winandy
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.tinylog.util;

/**
 * Utility class for string operations.
 */
public final class Strings {

	/** */
	private Strings() {
	}

	/**
	 * Tests whether a string is neither {@code null} nor empty. This method will return {@code true}, if the given
	 * string has at least one character (blank characters do count).
	 *
	 * @param value
	 *            String to test
	 * @return {@code true} if string has at least one character, otherwise {@code false}
	 */
	public static boolean isNeitherNullNorEmpty(final String value) {
		return value != null && !value.isEmpty();
	}

}
