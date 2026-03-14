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

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Tests for @Action annotation on IIActorRef subclass.
 *
 * <p>The @Action annotation is designed to be placed on IIActorRef subclass methods,
 * NOT on the wrapped POJO. This keeps the POJO clean - it doesn't need to know about
 * workflow-related concepts like {@link Action} or {@link ActionResult}.</p>
 *
 * <p>Design rationale:</p>
 * <ul>
 *   <li>POJOs should remain pure domain objects without workflow dependencies</li>
 *   <li>IIActorRef subclass acts as an adapter between POJO and workflow interpreter</li>
 *   <li>@Action methods on IIActorRef can delegate to POJO methods with appropriate translation</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 */
@DisplayName("@Action Annotation on IIActorRef Subclass")
public class ActionAnnotationTest {

    // ========================================================================
    // Test fixtures: Plain POJOs (no @Action annotations)
    // ========================================================================

    /**
     * Simple POJO calculator - no workflow annotations.
     * This is how POJOs should be: clean domain objects.
     */
    static class Calculator {
        private int lastResult = 0;

        public int add(int a, int b) {
            lastResult = a + b;
            return lastResult;
        }

        public int multiply(int a, int b) {
            lastResult = a * b;
            return lastResult;
        }

        public int getLastResult() {
            return lastResult;
        }
    }

    /**
     * Another simple POJO - a counter.
     */
    static class Counter {
        private int count = 0;

        public void increment() {
            count++;
        }

        public void reset() {
            count = 0;
        }

        public int getCount() {
            return count;
        }
    }

    // ========================================================================
    // Test fixtures: IIActorRef subclasses with @Action annotations
    // ========================================================================

    /**
     * IIActorRef for Calculator with @Action annotations.
     * This is the correct pattern - @Action on IIActorRef, not on POJO.
     */
    static class CalculatorIIAR extends IIActorRef<Calculator> {

        public CalculatorIIAR(String name, Calculator calculator) {
            super(name, calculator);
        }

        @Action("add")
        public ActionResult add(String args) {
            String[] parts = args.replace("[", "").replace("]", "").replace("\"", "").split(",");
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            int result = this.object.add(a, b);
            return new ActionResult(true, String.valueOf(result));
        }

        @Action("multiply")
        public ActionResult multiply(String args) {
            String[] parts = args.replace("[", "").replace("]", "").replace("\"", "").split(",");
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            int result = this.object.multiply(a, b);
            return new ActionResult(true, String.valueOf(result));
        }

        @Action("getLastResult")
        public ActionResult getLastResult(String args) {
            return new ActionResult(true, String.valueOf(this.object.getLastResult()));
        }
    }

    /**
     * IIActorRef for Counter with @Action annotations.
     */
    static class CounterIIAR extends IIActorRef<Counter> {

        public CounterIIAR(String name, Counter counter) {
            super(name, counter);
        }

        @Action("increment")
        public ActionResult increment(String args) {
            this.object.increment();
            return new ActionResult(true, "Count: " + this.object.getCount());
        }

        @Action("reset")
        public ActionResult reset(String args) {
            this.object.reset();
            return new ActionResult(true, "Reset to 0");
        }

        @Action("getCount")
        public ActionResult getCount(String args) {
            return new ActionResult(true, String.valueOf(this.object.getCount()));
        }
    }

    /**
     * IIActorRef with invalid @Action method signatures (for validation tests).
     */
    static class InvalidActionsIIAR extends IIActorRef<Counter> {

        public InvalidActionsIIAR(String name, Counter counter) {
            super(name, counter);
        }

        // Wrong return type - should be skipped
        @Action("wrongReturnType")
        public String wrongReturnType(String args) {
            return "wrong";
        }

        // Wrong parameter count (no params) - should be skipped
        @Action("noParams")
        public ActionResult noParams() {
            return new ActionResult(true, "no params");
        }

        // Wrong parameter count (two params) - should be skipped
        @Action("twoParams")
        public ActionResult twoParams(String a, String b) {
            return new ActionResult(true, "two params");
        }

        // Valid method
        @Action("valid")
        public ActionResult valid(String args) {
            return new ActionResult(true, "valid");
        }
    }

    /**
     * IIActorRef with @Action that throws exception.
     */
    static class ThrowingIIAR extends IIActorRef<Counter> {

        public ThrowingIIAR(String name, Counter counter) {
            super(name, counter);
        }

        @Action("throwException")
        public ActionResult throwException(String args) {
            throw new RuntimeException("Intentional test exception");
        }

        @Action("throwNPE")
        public ActionResult throwNPE(String args) {
            String s = null;
            return new ActionResult(true, s.length() + "");
        }
    }

    /**
     * Plain IIActorRef without any @Action methods (for fallback tests).
     */
    static class PlainIIAR extends IIActorRef<Counter> {

        public PlainIIAR(String name, Counter counter) {
            super(name, counter);
        }

        // No @Action methods - should only have built-in JSON actions
    }

    // ========================================================================
    // Tests: Basic @Action discovery on IIActorRef subclass
    // ========================================================================

    @Nested
    @DisplayName("Basic @Action Discovery")
    class BasicDiscovery {

        @Test
        @DisplayName("Should discover @Action on IIActorRef subclass")
        void shouldDiscoverActionOnIIActorRefSubclass() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            ActionResult result = actorRef.callByActionName("add", "[5, 3]");

            assertTrue(result.isSuccess());
            assertEquals("8", result.getResult());
        }

        @Test
        @DisplayName("Should discover multiple @Action methods")
        void shouldDiscoverMultipleActionMethods() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            ActionResult addResult = actorRef.callByActionName("add", "[10, 5]");
            ActionResult multiplyResult = actorRef.callByActionName("multiply", "[4, 7]");

            assertTrue(addResult.isSuccess());
            assertEquals("15", addResult.getResult());

            assertTrue(multiplyResult.isSuccess());
            assertEquals("28", multiplyResult.getResult());
        }

        @Test
        @DisplayName("@Action method should correctly delegate to POJO")
        void actionMethodShouldDelegateToPojo() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            actorRef.callByActionName("add", "[100, 50]");
            ActionResult lastResult = actorRef.callByActionName("getLastResult", "");

            assertEquals("150", lastResult.getResult());
            assertEquals(150, calc.getLastResult()); // Verify POJO was updated
        }

        @Test
        @DisplayName("Should work with different IIActorRef subclasses")
        void shouldWorkWithDifferentSubclasses() {
            Counter counter = new Counter();
            CounterIIAR actorRef = new CounterIIAR("counter", counter);

            actorRef.callByActionName("increment", "");
            actorRef.callByActionName("increment", "");
            actorRef.callByActionName("increment", "");

            ActionResult result = actorRef.callByActionName("getCount", "");
            assertEquals("3", result.getResult());
            assertEquals(3, counter.getCount());
        }
    }

    // ========================================================================
    // Tests: Unknown actions
    // ========================================================================

    @Nested
    @DisplayName("Unknown Actions")
    class UnknownActions {

        @Test
        @DisplayName("Should return failure for unknown action")
        void shouldReturnFailureForUnknownAction() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            ActionResult result = actorRef.callByActionName("unknownAction", "[]");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unknown action"));
        }

        @Test
        @DisplayName("IIActorRef without @Action methods should only have built-in actions")
        void iiActorRefWithoutActionsShouldOnlyHaveBuiltIn() {
            Counter counter = new Counter();
            PlainIIAR actorRef = new PlainIIAR("counter", counter);

            // Unknown action should fail
            ActionResult result = actorRef.callByActionName("increment", "");
            assertFalse(result.isSuccess());

            // But built-in putJson should work
            ActionResult putResult = actorRef.callByActionName("putJson",
                "{\"path\": \"key\", \"value\": \"value\"}");
            assertTrue(putResult.isSuccess());
        }
    }

    // ========================================================================
    // Tests: Method signature validation
    // ========================================================================

    @Nested
    @DisplayName("Method Signature Validation")
    class SignatureValidation {

        @Test
        @DisplayName("Should skip @Action with wrong return type")
        void shouldSkipActionWithWrongReturnType() {
            Counter counter = new Counter();
            InvalidActionsIIAR actorRef = new InvalidActionsIIAR("invalid", counter);

            ActionResult result = actorRef.callByActionName("wrongReturnType", "");
            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unknown action"));
        }

        @Test
        @DisplayName("Should skip @Action with no parameters")
        void shouldSkipActionWithNoParams() {
            Counter counter = new Counter();
            InvalidActionsIIAR actorRef = new InvalidActionsIIAR("invalid", counter);

            ActionResult result = actorRef.callByActionName("noParams", "");
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should skip @Action with two parameters")
        void shouldSkipActionWithTwoParams() {
            Counter counter = new Counter();
            InvalidActionsIIAR actorRef = new InvalidActionsIIAR("invalid", counter);

            ActionResult result = actorRef.callByActionName("twoParams", "");
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Valid @Action should still work when invalid ones exist")
        void validActionShouldWorkWhenInvalidOnesExist() {
            Counter counter = new Counter();
            InvalidActionsIIAR actorRef = new InvalidActionsIIAR("invalid", counter);

            ActionResult result = actorRef.callByActionName("valid", "");
            assertTrue(result.isSuccess());
            assertEquals("valid", result.getResult());
        }
    }

    // ========================================================================
    // Tests: Error handling
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should catch exception from @Action method")
        void shouldCatchExceptionFromActionMethod() {
            Counter counter = new Counter();
            ThrowingIIAR actorRef = new ThrowingIIAR("throwing", counter);

            ActionResult result = actorRef.callByActionName("throwException", "");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Intentional test exception"));
        }

        @Test
        @DisplayName("Should catch NPE from @Action method")
        void shouldCatchNPEFromActionMethod() {
            Counter counter = new Counter();
            ThrowingIIAR actorRef = new ThrowingIIAR("throwing", counter);

            ActionResult result = actorRef.callByActionName("throwNPE", "");

            assertFalse(result.isSuccess());
        }
    }

    // ========================================================================
    // Tests: Coexistence with built-in actions
    // ========================================================================

    @Nested
    @DisplayName("Coexistence with Built-in Actions")
    class CoexistenceWithBuiltIn {

        @Test
        @DisplayName("Built-in JSON actions should work alongside @Action")
        void builtInJsonActionsShouldWork() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            // Custom @Action should work
            ActionResult addResult = actorRef.callByActionName("add", "[1, 2]");
            assertTrue(addResult.isSuccess());
            assertEquals("3", addResult.getResult());

            // Built-in putJson should also work
            ActionResult putResult = actorRef.callByActionName("putJson",
                "{\"path\": \"testKey\", \"value\": \"testValue\"}");
            assertTrue(putResult.isSuccess());

            // Built-in getJson should also work
            ActionResult getResult = actorRef.callByActionName("getJson", "[\"testKey\"]");
            assertTrue(getResult.isSuccess());
            assertEquals("testValue", getResult.getResult());
        }

        @Test
        @DisplayName("@Action should be checked before built-in actions")
        void actionShouldBeCheckedBeforeBuiltIn() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            // First verify @Action works
            ActionResult customResult = actorRef.callByActionName("add", "[5, 5]");
            assertTrue(customResult.isSuccess());

            // Then verify built-in action works
            ActionResult clearResult = actorRef.callByActionName("clearJson", "");
            assertTrue(clearResult.isSuccess());
        }
    }

    // ========================================================================
    // Tests: Caching behavior
    // ========================================================================

    @Nested
    @DisplayName("Action Method Caching")
    class CachingBehavior {

        @Test
        @DisplayName("Multiple calls should use cached method lookup")
        void multipleCallsShouldUseCachedLookup() {
            Calculator calc = new Calculator();
            CalculatorIIAR actorRef = new CalculatorIIAR("calc", calc);

            // First call triggers discovery
            ActionResult result1 = actorRef.callByActionName("add", "[1, 1]");
            assertEquals("2", result1.getResult());

            // Subsequent calls use cache
            ActionResult result2 = actorRef.callByActionName("add", "[2, 2]");
            assertEquals("4", result2.getResult());

            ActionResult result3 = actorRef.callByActionName("multiply", "[3, 3]");
            assertEquals("9", result3.getResult());

            ActionResult result4 = actorRef.callByActionName("add", "[100, 200]");
            assertEquals("300", result4.getResult());

            assertTrue(result1.isSuccess());
            assertTrue(result2.isSuccess());
            assertTrue(result3.isSuccess());
            assertTrue(result4.isSuccess());
        }

        @Test
        @DisplayName("Different actor instances should have separate caches")
        void differentActorsShouldHaveSeparateCaches() {
            Calculator calc1 = new Calculator();
            Calculator calc2 = new Calculator();
            CalculatorIIAR actor1 = new CalculatorIIAR("calc1", calc1);
            CalculatorIIAR actor2 = new CalculatorIIAR("calc2", calc2);

            actor1.callByActionName("add", "[10, 20]");
            actor2.callByActionName("add", "[100, 200]");

            assertEquals(30, calc1.getLastResult());
            assertEquals(300, calc2.getLastResult());
        }
    }

    // ========================================================================
    // Tests: POJO remains clean (design verification)
    // ========================================================================

    @Nested
    @DisplayName("POJO Remains Clean")
    class PojoRemainsClean {

        @Test
        @DisplayName("POJO should not have @Action annotations")
        void pojoShouldNotHaveActionAnnotations() throws NoSuchMethodException {
            // Verify Calculator.add has no @Action annotation
            var method = Calculator.class.getMethod("add", int.class, int.class);
            Action action = method.getAnnotation(Action.class);
            assertNull(action, "POJO methods should not have @Action annotation");
        }

        @Test
        @DisplayName("POJO should not depend on ActionResult")
        void pojoShouldNotDependOnActionResult() throws NoSuchMethodException {
            // Verify Calculator.add returns int, not ActionResult
            var method = Calculator.class.getMethod("add", int.class, int.class);
            assertEquals(int.class, method.getReturnType(),
                "POJO methods should return domain types, not ActionResult");
        }

        @Test
        @DisplayName("IIActorRef subclass should have @Action annotations")
        void iiActorRefSubclassShouldHaveActionAnnotations() throws NoSuchMethodException {
            // Verify CalculatorIIAR.add has @Action annotation
            var method = CalculatorIIAR.class.getMethod("add", String.class);
            Action action = method.getAnnotation(Action.class);
            assertNotNull(action, "IIActorRef subclass should have @Action annotation");
            assertEquals("add", action.value());
        }
    }
}
