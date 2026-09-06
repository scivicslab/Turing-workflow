/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.pojoactor.action;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Utility class for parsing arguments in {@link Action}-annotated methods.
 *
 * <p>YAML workflow {@code arguments} are passed to {@code @Action} methods as JSON strings.
 * This class provides convenient static methods to parse these JSON strings into
 * Java types without boilerplate code.</p>
 *
 * <h2>Argument Formats</h2>
 *
 * <p>YAML {@code arguments} can be written in three formats:</p>
 * <table>
 *   <tr><th>YAML format</th><th>Passed as JSON string</th></tr>
 *   <tr><td>{@code arguments: "value"}</td><td>{@code ["value"]}</td></tr>
 *   <tr><td>{@code arguments: ["a", "b"]}</td><td>{@code ["a", "b"]}</td></tr>
 *   <tr><td>{@code arguments: {key: "value"}}</td><td>{@code {"key": "value"}}</td></tr>
 * </table>
 *
 * <p>Single string arguments are wrapped as arrays. This simplifies parsing
 * to two patterns: array format and object format.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Single argument (most common)</h3>
 * <pre>{@code
 * @Action("put")
 * public ActionResult put(String args) {
 *     String value = ActionArgs.getFirst(args);
 *     this.object.put(value);
 *     return new ActionResult(true, "Put " + value);
 * }
 * }</pre>
 *
 * <h3>Multiple arguments</h3>
 * <pre>{@code
 * @Action("add")
 * public ActionResult add(String args) {
 *     int a = ActionArgs.getInt(args, 0);
 *     int b = ActionArgs.getInt(args, 1);
 *     return new ActionResult(true, String.valueOf(a + b));
 * }
 * }</pre>
 *
 * <h3>Object arguments</h3>
 * <pre>{@code
 * @Action("configure")
 * public ActionResult configure(String args) {
 *     String host = ActionArgs.getString(args, "hostname");
 *     int port = ActionArgs.getInt(args, "port", 80);
 *     boolean ssl = ActionArgs.getBoolean(args, "ssl", false);
 *     // ...
 * }
 * }</pre>
 *
 * <h3>With static import</h3>
 * <pre>{@code
 * import static com.scivicslab.pojoactor.action.ActionArgs.*;
 *
 * @Action("move")
 * public ActionResult move(String args) {
 *     String direction = getFirst(args);
 *     // ...
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 * @see Action
 * @see ActionResult
 */
public final class ActionArgs {

    private ActionArgs() {
        // Utility class - no instantiation
    }

    // ========================================================================
    // Unified parsing - ParsedArgs
    // ========================================================================

    /**
     * Parses arguments into a unified accessor.
     *
     * <p>This is the recommended way to handle arguments in {@code @Action} methods.
     * It provides a unified interface regardless of whether the YAML used
     * array format or object format.</p>
     *
     * <pre>{@code
     * @Action("example")
     * public ActionResult example(String args) {
     *     ParsedArgs p = ActionArgs.parse(args);
     *     String value = p.get(0);           // positional access
     *     String host = p.get("host");       // named access
     *     int port = p.getInt("port", 80);   // with default
     * }
     * }</pre>
     *
     * @param args JSON string from @Action method parameter
     * @return ParsedArgs for unified access
     */
    public static ParsedArgs parse(String args) {
        return new ParsedArgs(args);
    }

    /**
     * Unified accessor for action arguments.
     *
     * <p>Provides consistent access to arguments regardless of whether
     * they came from array format or object format in YAML.</p>
     */
    public static class ParsedArgs {
        private final String raw;
        private final boolean isArrayFormat;
        private final boolean isObjectFormat;
        private JSONArray array;
        private JSONObject object;

        ParsedArgs(String args) {
            this.raw = args;
            this.isArrayFormat = ActionArgs.isArray(args);
            this.isObjectFormat = ActionArgs.isObject(args);

            if (isArrayFormat) {
                this.array = ActionArgs.asArray(args);
            } else if (isObjectFormat) {
                this.object = ActionArgs.asObject(args);
            }
        }

        /**
         * Gets the raw JSON string.
         *
         * @return the original args string
         */
        public String raw() {
            return raw;
        }

        /**
         * Checks if arguments are in array format.
         *
         * @return true if array format
         */
        public boolean isArray() {
            return isArrayFormat;
        }

        /**
         * Checks if arguments are in object format.
         *
         * @return true if object format
         */
        public boolean isObject() {
            return isObjectFormat;
        }

        /**
         * Gets the number of elements (for array format).
         *
         * @return array length, or 0 if not array format
         */
        public int length() {
            return array != null ? array.length() : 0;
        }

        /**
         * Checks if arguments are empty.
         *
         * @return true if empty
         */
        public boolean isEmpty() {
            return ActionArgs.isEmpty(raw);
        }

        // --- Positional access (for array format) ---

        /**
         * Gets string at index (for array format).
         *
         * @param index 0-based index
         * @return string value, or empty string if not found
         */
        public String get(int index) {
            return get(index, "");
        }

        /**
         * Gets string at index with default (for array format).
         *
         * @param index 0-based index
         * @param defaultValue default if not found
         * @return string value
         */
        public String get(int index, String defaultValue) {
            if (array == null || index < 0 || index >= array.length()) {
                return defaultValue;
            }
            return array.optString(index, defaultValue);
        }

        /**
         * Gets integer at index (for array format).
         *
         * @param index 0-based index
         * @return int value, or 0 if not found
         */
        public int getInt(int index) {
            return getInt(index, 0);
        }

        /**
         * Gets integer at index with default (for array format).
         *
         * @param index 0-based index
         * @param defaultValue default if not found
         * @return int value
         */
        public int getInt(int index, int defaultValue) {
            if (array == null || index < 0 || index >= array.length()) {
                return defaultValue;
            }
            return array.optInt(index, defaultValue);
        }

        /**
         * Gets boolean at index (for array format).
         *
         * @param index 0-based index
         * @return boolean value, or false if not found
         */
        public boolean getBoolean(int index) {
            return getBoolean(index, false);
        }

        /**
         * Gets boolean at index with default (for array format).
         *
         * @param index 0-based index
         * @param defaultValue default if not found
         * @return boolean value
         */
        public boolean getBoolean(int index, boolean defaultValue) {
            if (array == null || index < 0 || index >= array.length()) {
                return defaultValue;
            }
            return array.optBoolean(index, defaultValue);
        }

        // --- Named access (for object format) ---

        /**
         * Gets string by key (for object format).
         *
         * @param key key name
         * @return string value, or empty string if not found
         */
        public String get(String key) {
            return get(key, "");
        }

        /**
         * Gets string by key with default (for object format).
         *
         * @param key key name
         * @param defaultValue default if not found
         * @return string value
         */
        public String get(String key, String defaultValue) {
            if (object == null) {
                return defaultValue;
            }
            return object.optString(key, defaultValue);
        }

        /**
         * Gets integer by key (for object format).
         *
         * @param key key name
         * @return int value, or 0 if not found
         */
        public int getInt(String key) {
            return getInt(key, 0);
        }

        /**
         * Gets integer by key with default (for object format).
         *
         * @param key key name
         * @param defaultValue default if not found
         * @return int value
         */
        public int getInt(String key, int defaultValue) {
            if (object == null) {
                return defaultValue;
            }
            return object.optInt(key, defaultValue);
        }

        /**
         * Gets boolean by key (for object format).
         *
         * @param key key name
         * @return boolean value, or false if not found
         */
        public boolean getBoolean(String key) {
            return getBoolean(key, false);
        }

        /**
         * Gets boolean by key with default (for object format).
         *
         * @param key key name
         * @param defaultValue default if not found
         * @return boolean value
         */
        public boolean getBoolean(String key, boolean defaultValue) {
            if (object == null) {
                return defaultValue;
            }
            return object.optBoolean(key, defaultValue);
        }

        /**
         * Checks if key exists (for object format).
         *
         * @param key key name
         * @return true if key exists
         */
        public boolean has(String key) {
            return object != null && object.has(key);
        }

        /**
         * Gets the underlying JSONArray (for array format).
         *
         * @return JSONArray, or null if not array format
         */
        public JSONArray asArray() {
            return array;
        }

        /**
         * Gets the underlying JSONObject (for object format).
         *
         * @return JSONObject, or null if not object format
         */
        public JSONObject asObject() {
            return object;
        }
    }

    // ========================================================================
    // Array format parsing: arguments: ["a", "b"] or arguments: "value"
    // ========================================================================

    /**
     * Parses the arguments as a JSONArray.
     *
     * @param args JSON string (array format)
     * @return JSONArray, or empty JSONArray if args is null/empty
     */
    public static JSONArray asArray(String args) {
        if (args == null || args.isEmpty() || args.equals("[]")) {
            return new JSONArray();
        }
        try {
            return new JSONArray(args);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /**
     * Gets a string at the specified index from array arguments.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @return string value, or empty string if not found
     */
    public static String getString(String args, int index) {
        return getString(args, index, "");
    }

    /**
     * Gets a string at the specified index from array arguments with default.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @param defaultValue default value if not found
     * @return string value
     */
    public static String getString(String args, int index, String defaultValue) {
        JSONArray array = asArray(args);
        if (index < 0 || index >= array.length()) {
            return defaultValue;
        }
        return array.optString(index, defaultValue);
    }

    /**
     * Gets the first string from array arguments.
     *
     * <p>This is a shortcut for the most common case: single argument.</p>
     *
     * @param args JSON string (array format)
     * @return first string, or empty string if not found
     */
    public static String getFirst(String args) {
        return getString(args, 0);
    }

    /**
     * Gets an integer at the specified index from array arguments.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @return integer value, or 0 if not found or parse fails
     */
    public static int getInt(String args, int index) {
        return getInt(args, index, 0);
    }

    /**
     * Gets an integer at the specified index from array arguments with default.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @param defaultValue default value if not found
     * @return integer value
     */
    public static int getInt(String args, int index, int defaultValue) {
        JSONArray array = asArray(args);
        if (index < 0 || index >= array.length()) {
            return defaultValue;
        }
        return array.optInt(index, defaultValue);
    }

    /**
     * Gets a long at the specified index from array arguments.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @return long value, or 0 if not found or parse fails
     */
    public static long getLong(String args, int index) {
        return getLong(args, index, 0L);
    }

    /**
     * Gets a long at the specified index from array arguments with default.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @param defaultValue default value if not found
     * @return long value
     */
    public static long getLong(String args, int index, long defaultValue) {
        JSONArray array = asArray(args);
        if (index < 0 || index >= array.length()) {
            return defaultValue;
        }
        return array.optLong(index, defaultValue);
    }

    /**
     * Gets a double at the specified index from array arguments.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @return double value, or 0.0 if not found or parse fails
     */
    public static double getDouble(String args, int index) {
        return getDouble(args, index, 0.0);
    }

    /**
     * Gets a double at the specified index from array arguments with default.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @param defaultValue default value if not found
     * @return double value
     */
    public static double getDouble(String args, int index, double defaultValue) {
        JSONArray array = asArray(args);
        if (index < 0 || index >= array.length()) {
            return defaultValue;
        }
        return array.optDouble(index, defaultValue);
    }

    /**
     * Gets a boolean at the specified index from array arguments.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @return boolean value, or false if not found
     */
    public static boolean getBoolean(String args, int index) {
        return getBoolean(args, index, false);
    }

    /**
     * Gets a boolean at the specified index from array arguments with default.
     *
     * @param args JSON string (array format)
     * @param index index (0-based)
     * @param defaultValue default value if not found
     * @return boolean value
     */
    public static boolean getBoolean(String args, int index, boolean defaultValue) {
        JSONArray array = asArray(args);
        if (index < 0 || index >= array.length()) {
            return defaultValue;
        }
        return array.optBoolean(index, defaultValue);
    }

    /**
     * Gets the length of array arguments.
     *
     * @param args JSON string (array format)
     * @return array length
     */
    public static int length(String args) {
        return asArray(args).length();
    }

    // ========================================================================
    // Object format parsing: arguments: {key: "value"}
    // ========================================================================

    /**
     * Parses the arguments as a JSONObject.
     *
     * @param args JSON string (object format)
     * @return JSONObject, or empty JSONObject if args is null/empty
     */
    public static JSONObject asObject(String args) {
        if (args == null || args.isEmpty() || args.equals("{}")) {
            return new JSONObject();
        }
        try {
            return new JSONObject(args);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * Gets a string for the specified key from object arguments.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @return string value, or empty string if not found
     */
    public static String getString(String args, String key) {
        return getString(args, key, "");
    }

    /**
     * Gets a string for the specified key from object arguments with default.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @param defaultValue default value if not found
     * @return string value
     */
    public static String getString(String args, String key, String defaultValue) {
        JSONObject obj = asObject(args);
        return obj.optString(key, defaultValue);
    }

    /**
     * Gets an integer for the specified key from object arguments.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @return integer value, or 0 if not found
     */
    public static int getInt(String args, String key) {
        return getInt(args, key, 0);
    }

    /**
     * Gets an integer for the specified key from object arguments with default.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @param defaultValue default value if not found
     * @return integer value
     */
    public static int getInt(String args, String key, int defaultValue) {
        JSONObject obj = asObject(args);
        return obj.optInt(key, defaultValue);
    }

    /**
     * Gets a long for the specified key from object arguments.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @return long value, or 0 if not found
     */
    public static long getLong(String args, String key) {
        return getLong(args, key, 0L);
    }

    /**
     * Gets a long for the specified key from object arguments with default.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @param defaultValue default value if not found
     * @return long value
     */
    public static long getLong(String args, String key, long defaultValue) {
        JSONObject obj = asObject(args);
        return obj.optLong(key, defaultValue);
    }

    /**
     * Gets a double for the specified key from object arguments.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @return double value, or 0.0 if not found
     */
    public static double getDouble(String args, String key) {
        return getDouble(args, key, 0.0);
    }

    /**
     * Gets a double for the specified key from object arguments with default.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @param defaultValue default value if not found
     * @return double value
     */
    public static double getDouble(String args, String key, double defaultValue) {
        JSONObject obj = asObject(args);
        return obj.optDouble(key, defaultValue);
    }

    /**
     * Gets a boolean for the specified key from object arguments.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @return boolean value, or false if not found
     */
    public static boolean getBoolean(String args, String key) {
        return getBoolean(args, key, false);
    }

    /**
     * Gets a boolean for the specified key from object arguments with default.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @param defaultValue default value if not found
     * @return boolean value
     */
    public static boolean getBoolean(String args, String key, boolean defaultValue) {
        JSONObject obj = asObject(args);
        return obj.optBoolean(key, defaultValue);
    }

    /**
     * Checks if the specified key exists in object arguments.
     *
     * @param args JSON string (object format)
     * @param key key name
     * @return true if key exists
     */
    public static boolean hasKey(String args, String key) {
        return asObject(args).has(key);
    }

    // ========================================================================
    // Type detection
    // ========================================================================

    /**
     * Checks if arguments are in array format.
     *
     * <p>Returns true if args starts with '['. This includes single string
     * arguments which are wrapped as arrays (e.g., "value" becomes ["value"]).</p>
     *
     * @param args JSON string
     * @return true if args is array format
     */
    public static boolean isArray(String args) {
        if (args == null || args.isEmpty()) {
            return false;
        }
        String trimmed = args.trim();
        return trimmed.startsWith("[");
    }

    /**
     * Checks if arguments are in object format.
     *
     * <p>Returns true if args starts with '{'. This is used for named
     * arguments (e.g., {host: "server1", port: 8080}).</p>
     *
     * @param args JSON string
     * @return true if args is object format
     */
    public static boolean isObject(String args) {
        if (args == null || args.isEmpty()) {
            return false;
        }
        String trimmed = args.trim();
        return trimmed.startsWith("{");
    }

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Checks if array arguments have at least the required number of elements.
     *
     * @param args JSON string (array format)
     * @param required required number of arguments
     * @return true if array has at least {@code required} elements
     */
    public static boolean hasAtLeast(String args, int required) {
        return length(args) >= required;
    }

    /**
     * Checks if arguments are empty.
     *
     * @param args JSON string
     * @return true if args is null, empty, "[]", or "{}"
     */
    public static boolean isEmpty(String args) {
        return args == null || args.isEmpty()
            || args.equals("[]") || args.equals("{}");
    }

    /**
     * Checks if arguments are not empty.
     *
     * @param args JSON string
     * @return true if args is not empty
     */
    public static boolean isNotEmpty(String args) {
        return !isEmpty(args);
    }
}
