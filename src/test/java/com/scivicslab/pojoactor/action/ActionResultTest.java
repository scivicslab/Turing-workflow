/*
 * Copyright 2025 SCIVICS Lab
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
package com.scivicslab.pojoactor.action;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for ActionResult class.
 *
 * <p>ActionResult is a value object that encapsulates the outcome of an action
 * execution in the CallableByActionName pattern. These tests verify all aspects
 * of its behavior following the Specification by Example approach.</p>
 *
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
@Tag("S_svc.01")
@DisplayName("ActionResult Specification by Example")
public class ActionResultTest {

    // ========================================================================
    // Part 1: Basic Construction and Getter Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 1: Basic Construction")
    class BasicConstructionTests {

        /**
         * Example 1: Successful result with message.
         */
        @Test
        @DisplayName("Should create successful ActionResult with result message")
        public void testSuccessfulResult() {
            ActionResult result = new ActionResult(true, "Operation completed");

            assertTrue(result.isSuccess(), "isSuccess should return true");
            assertEquals("Operation completed", result.getResult(),
                "getResult should return the provided message");
        }

        /**
         * Example 2: Failed result with error message.
         */
        @Test
        @DisplayName("Should create failed ActionResult with error message")
        public void testFailedResult() {
            ActionResult result = new ActionResult(false, "Error: Invalid input");

            assertFalse(result.isSuccess(), "isSuccess should return false");
            assertEquals("Error: Invalid input", result.getResult(),
                "getResult should return the error message");
        }

        /**
         * Example 3: Result with numeric string value.
         */
        @Test
        @DisplayName("Should handle numeric string results")
        public void testNumericResult() {
            ActionResult result = new ActionResult(true, "42");

            assertTrue(result.isSuccess());
            assertEquals("42", result.getResult());
            assertEquals(42, Integer.parseInt(result.getResult()),
                "Result should be parseable as integer");
        }

        /**
         * Example 4: Result with empty string.
         */
        @Test
        @DisplayName("Should handle empty string result")
        public void testEmptyStringResult() {
            ActionResult result = new ActionResult(true, "");

            assertTrue(result.isSuccess());
            assertEquals("", result.getResult(), "Empty string should be preserved");
            assertTrue(result.getResult().isEmpty());
        }

        /**
         * Example 5: Result with null value.
         */
        @Test
        @DisplayName("Should handle null result value")
        public void testNullResult() {
            ActionResult result = new ActionResult(true, null);

            assertTrue(result.isSuccess());
            assertNull(result.getResult(), "Null should be preserved");
        }
    }

    // ========================================================================
    // Part 2: toString() Method Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 2: toString() Method")
    class ToStringTests {

        /**
         * Example 6: toString for successful result.
         */
        @Test
        @DisplayName("toString should include success=true for successful result")
        public void testToStringSuccess() {
            ActionResult result = new ActionResult(true, "test data");
            String str = result.toString();

            assertTrue(str.contains("success=true"),
                "toString should include 'success=true'");
            assertTrue(str.contains("test data"),
                "toString should include result value");
            assertTrue(str.startsWith("ActionResult{"),
                "toString should start with class name");
        }

        /**
         * Example 7: toString for failed result.
         */
        @Test
        @DisplayName("toString should include success=false for failed result")
        public void testToStringFailure() {
            ActionResult result = new ActionResult(false, "error message");
            String str = result.toString();

            assertTrue(str.contains("success=false"),
                "toString should include 'success=false'");
            assertTrue(str.contains("error message"),
                "toString should include error message");
        }

        /**
         * Example 8: toString with special characters.
         */
        @Test
        @DisplayName("toString should handle special characters in result")
        public void testToStringSpecialCharacters() {
            ActionResult result = new ActionResult(true, "line1\nline2\ttab");
            String str = result.toString();

            assertTrue(str.contains("line1"),
                "toString should include content with special chars");
        }

        /**
         * Example 9: toString with null result.
         */
        @Test
        @DisplayName("toString should handle null result gracefully")
        public void testToStringNullResult() {
            ActionResult result = new ActionResult(false, null);
            String str = result.toString();

            assertNotNull(str, "toString should not return null");
            assertTrue(str.contains("success=false"));
            assertTrue(str.contains("null") || str.contains("result='null'"),
                "toString should represent null value");
        }
    }

    // ========================================================================
    // Part 3: Immutability Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 3: Immutability")
    class ImmutabilityTests {

        /**
         * Example 10: Fields should be immutable after construction.
         */
        @Test
        @DisplayName("ActionResult should be immutable")
        public void testImmutability() {
            ActionResult result = new ActionResult(true, "original");

            // Verify initial state
            assertTrue(result.isSuccess());
            assertEquals("original", result.getResult());

            // Calling getters multiple times should return same values
            boolean success1 = result.isSuccess();
            boolean success2 = result.isSuccess();
            String value1 = result.getResult();
            String value2 = result.getResult();

            assertEquals(success1, success2, "isSuccess should be consistent");
            assertEquals(value1, value2, "getResult should be consistent");

            // No setter methods exist - immutability by design
        }

        /**
         * Example 11: Multiple instances should be independent.
         */
        @Test
        @DisplayName("Multiple ActionResult instances should be independent")
        public void testInstanceIndependence() {
            ActionResult result1 = new ActionResult(true, "value1");
            ActionResult result2 = new ActionResult(false, "value2");

            // Verify independence
            assertTrue(result1.isSuccess());
            assertFalse(result2.isSuccess());
            assertEquals("value1", result1.getResult());
            assertEquals("value2", result2.getResult());

            // Creating new instances doesn't affect existing ones
            ActionResult result3 = new ActionResult(true, "value3");
            assertEquals("value1", result1.getResult(),
                "Original instance should not be affected");
        }
    }

    // ========================================================================
    // Part 4: Edge Cases and Boundary Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 4: Edge Cases")
    class EdgeCaseTests {

        /**
         * Example 12: Very long result string.
         */
        @Test
        @DisplayName("Should handle very long result strings")
        public void testLongResultString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                sb.append("a");
            }
            String longString = sb.toString();

            ActionResult result = new ActionResult(true, longString);

            assertTrue(result.isSuccess());
            assertEquals(10000, result.getResult().length());
            assertEquals(longString, result.getResult());
        }

        /**
         * Example 13: Unicode characters in result.
         */
        @Test
        @DisplayName("Should handle Unicode characters")
        public void testUnicodeCharacters() {
            String unicode = "日本語テスト 한글 中文 🚀";

            ActionResult result = new ActionResult(true, unicode);

            assertTrue(result.isSuccess());
            assertEquals(unicode, result.getResult());
            assertTrue(result.getResult().contains("日本語"));
            assertTrue(result.getResult().contains("🚀"));
        }

        /**
         * Example 14: Whitespace-only result.
         */
        @Test
        @DisplayName("Should preserve whitespace-only result")
        public void testWhitespaceOnlyResult() {
            ActionResult result = new ActionResult(true, "   ");

            assertTrue(result.isSuccess());
            assertEquals("   ", result.getResult());
            assertEquals(3, result.getResult().length());
        }

        /**
         * Example 15: JSON-formatted result.
         */
        @Test
        @DisplayName("Should handle JSON-formatted result string")
        public void testJsonFormattedResult() {
            String json = "{\"key\": \"value\", \"number\": 42}";

            ActionResult result = new ActionResult(true, json);

            assertTrue(result.isSuccess());
            assertEquals(json, result.getResult());
            assertTrue(result.getResult().contains("\"key\""));
        }

        /**
         * Example 16: XML-formatted result.
         */
        @Test
        @DisplayName("Should handle XML-formatted result string")
        public void testXmlFormattedResult() {
            String xml = "<result><status>ok</status><value>123</value></result>";

            ActionResult result = new ActionResult(true, xml);

            assertTrue(result.isSuccess());
            assertEquals(xml, result.getResult());
        }
    }

    // ========================================================================
    // Part 5: Usage Pattern Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 5: Usage Patterns")
    class UsagePatternTests {

        /**
         * Example 17: Pattern for success result factory.
         */
        @Test
        @DisplayName("Should support success result pattern")
        public void testSuccessPattern() {
            // Common pattern: creating success result with computed value
            int computedValue = 10 + 20;
            ActionResult result = new ActionResult(true, String.valueOf(computedValue));

            assertTrue(result.isSuccess());
            assertEquals("30", result.getResult());
        }

        /**
         * Example 18: Pattern for error result factory.
         */
        @Test
        @DisplayName("Should support error result pattern")
        public void testErrorPattern() {
            // Common pattern: creating error result with exception message
            Exception ex = new IllegalArgumentException("Invalid parameter");
            ActionResult result = new ActionResult(false, "Error: " + ex.getMessage());

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Invalid parameter"));
        }

        /**
         * Example 19: Pattern for conditional result.
         */
        @Test
        @DisplayName("Should support conditional result pattern")
        public void testConditionalResultPattern() {
            // Common pattern: success or failure based on condition
            int value = 50;
            boolean isValid = value > 0 && value < 100;

            ActionResult result = isValid
                ? new ActionResult(true, "Value is valid: " + value)
                : new ActionResult(false, "Value out of range");

            assertTrue(result.isSuccess());
            assertTrue(result.getResult().contains("50"));
        }

        /**
         * Example 20: Pattern for chained result checking.
         */
        @Test
        @DisplayName("Should support chained result checking pattern")
        public void testChainedResultPattern() {
            ActionResult step1 = new ActionResult(true, "10");
            ActionResult step2;

            if (step1.isSuccess()) {
                int value = Integer.parseInt(step1.getResult());
                step2 = new ActionResult(true, String.valueOf(value * 2));
            } else {
                step2 = new ActionResult(false, "Previous step failed");
            }

            assertTrue(step2.isSuccess());
            assertEquals("20", step2.getResult());
        }

        /**
         * Example 21: Result used in stream/functional pattern.
         */
        @Test
        @DisplayName("Should work in functional programming patterns")
        public void testFunctionalPattern() {
            ActionResult result = new ActionResult(true, "42");

            // Map pattern (manual since ActionResult doesn't have map)
            String mappedValue = result.isSuccess()
                ? "Success: " + result.getResult()
                : "Failure: " + result.getResult();

            assertEquals("Success: 42", mappedValue);
        }
    }

    // ========================================================================
    // Part 6: CallableByActionName Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 6: CallableByActionName Integration")
    class CallableByActionNameIntegrationTests {

        /**
         * Example 22: ActionResult from CallableByActionName success.
         */
        @Test
        @DisplayName("Should integrate with CallableByActionName success scenario")
        public void testCallableByActionNameSuccess() {
            // Simulating a plugin's callByActionName return
            ActionResult result = simulateAction("add", "5,3");

            assertTrue(result.isSuccess());
            assertEquals("8", result.getResult());
        }

        /**
         * Example 23: ActionResult from CallableByActionName failure.
         */
        @Test
        @DisplayName("Should integrate with CallableByActionName failure scenario")
        public void testCallableByActionNameFailure() {
            // Simulating unknown action
            ActionResult result = simulateAction("unknownAction", "");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unknown action"));
        }

        /**
         * Example 24: ActionResult preserves error details.
         */
        @Test
        @DisplayName("Should preserve detailed error information")
        public void testErrorDetailPreservation() {
            ActionResult result = simulateAction("add", "invalid");

            assertFalse(result.isSuccess());
            assertNotNull(result.getResult());
            assertTrue(result.getResult().length() > 0,
                "Error message should not be empty");
        }

        // Helper method to simulate CallableByActionName behavior
        private ActionResult simulateAction(String actionName, String args) {
            try {
                switch (actionName) {
                    case "add":
                        String[] parts = args.split(",");
                        if (parts.length != 2) {
                            return new ActionResult(false, "Invalid argument count");
                        }
                        int a = Integer.parseInt(parts[0].trim());
                        int b = Integer.parseInt(parts[1].trim());
                        return new ActionResult(true, String.valueOf(a + b));
                    default:
                        return new ActionResult(false, "Unknown action: " + actionName);
                }
            } catch (NumberFormatException e) {
                return new ActionResult(false, "Number format error: " + e.getMessage());
            }
        }
    }
}
