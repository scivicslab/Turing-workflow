/*
 * Copyright 2026 devteam@scivicslab.com
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

package com.scivicslab.turingworkflow.workflow;

/**
 * One piece of text a workflow can read and change.
 *
 * <p>Methods return the type of what they answer: {@code length} an {@code int},
 * {@code contains} a {@code boolean}, the rest the text itself. A caller reaching this object
 * through an expression therefore receives a value rather than its printed form.
 * {@code StringActor} wraps this and carries the workflow vocabulary.</p>
 *
 * <p>The text is never {@code null}; setting {@code null} stores the empty string.</p>
 *
 * <p>Not thread-safe on its own. The actor that wraps it serialises access.</p>
 *
 * @author devteam@scivicslab.com
 */
public class TextSlot {

    private String text;

    /** Starts empty. */
    public TextSlot() {
        this("");
    }

    /**
     * Starts with the given text.
     *
     * @param text the text to hold; {@code null} stores the empty string
     */
    public TextSlot(String text) {
        this.text = text == null ? "" : text;
    }

    /** @return the text held now; never {@code null} */
    public String get() {
        return text;
    }

    /**
     * Replaces the text held.
     *
     * @param text the text to hold; {@code null} stores the empty string
     * @return the text now held
     */
    public String set(String text) {
        this.text = text == null ? "" : text;
        return this.text;
    }

    /** @return the text after emptying it */
    public String clear() {
        return set("");
    }

    /**
     * Adds text to the end.
     *
     * @param suffix the text to add; {@code null} adds nothing
     * @return the text now held
     */
    public String append(String suffix) {
        text = text + (suffix == null ? "" : suffix);
        return text;
    }

    /** @return how many characters the text has */
    public int length() {
        return text.length();
    }

    /** @return whether the text has no characters */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** @return the text after removing leading and trailing whitespace */
    public String trim() {
        return set(text.trim());
    }

    /** @return the text after converting it to upper case */
    public String toUpperCase() {
        return set(text.toUpperCase());
    }

    /** @return the text after converting it to lower case */
    public String toLowerCase() {
        return set(text.toLowerCase());
    }

    /**
     * @param part the text to look for; {@code null} is treated as the empty string
     * @return whether the text contains {@code part}
     */
    public boolean contains(String part) {
        return text.contains(part == null ? "" : part);
    }

    /**
     * @param prefix the text to look for at the start; {@code null} is treated as empty
     * @return whether the text starts with {@code prefix}
     */
    public boolean startsWith(String prefix) {
        return text.startsWith(prefix == null ? "" : prefix);
    }

    /**
     * @param suffix the text to look for at the end; {@code null} is treated as empty
     * @return whether the text ends with {@code suffix}
     */
    public boolean endsWith(String suffix) {
        return text.endsWith(suffix == null ? "" : suffix);
    }

    /**
     * Replaces every occurrence of one piece of text with another.
     *
     * @param target      the text to look for
     * @param replacement the text to put in its place
     * @return the text now held
     */
    public String replace(String target, String replacement) {
        return set(text.replace(target, replacement));
    }

    /**
     * @param start the position to start from, counted from zero
     * @return the text from {@code start} to the end; the held text is unchanged
     */
    public String substring(int start) {
        return text.substring(start);
    }

    /**
     * @param start the position to start from, counted from zero
     * @param end   the position to stop before
     * @return that part of the text; the held text is unchanged
     */
    public String substring(int start, int end) {
        return text.substring(start, end);
    }

    /**
     * Escapes the text so that it can sit inside a JSON string literal.
     *
     * @return the text now held
     */
    public String escapeJson() {
        return set(escapeForJson(text));
    }

    /**
     * Escapes text for a JSON string literal without changing what this slot holds.
     *
     * @param raw the text to escape; {@code null} yields the empty string
     * @return the escaped text
     */
    public static String escapeForJson(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default   -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** @return the text held now */
    @Override
    public String toString() {
        return text;
    }
}
