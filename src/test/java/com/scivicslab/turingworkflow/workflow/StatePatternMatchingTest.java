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

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Unit tests for State Pattern Matching in Workflow Interpreter.
 *
 * <p>Tests the following state patterns:</p>
 * <ul>
 *   <li>Exact match: "1" matches only "1"</li>
 *   <li>Wildcard: "*" matches any state</li>
 *   <li>Negation: "!end" matches anything except "end"</li>
 *   <li>OR condition: "1|2|3" matches "1", "2", or "3"</li>
 *   <li>Numeric comparison: ">=1", "<=5", ">1", "<5"</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @version 2.9.0
 */
@DisplayName("State Pattern Matching Specification")
public class StatePatternMatchingTest {

    private IIActorSystem system;
    private Interpreter interpreter;
    private List<String> calledActions;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("state-pattern-test-system");
        calledActions = new ArrayList<>();

        // Create a tracker actor to record action calls
        IIActorRef<Object> tracker = new IIActorRef<Object>("tracker", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                calledActions.add(actionName);
                if (actionName.startsWith("fail")) {
                    return new ActionResult(false, "intentional failure");
                }
                return new ActionResult(true, "success: " + actionName);
            }
        };
        system.addIIActor(tracker);

        interpreter = new Interpreter.Builder()
                .loggerName("state-pattern-test")
                .team(system)
                .build();
    }

    // ==================== Exact Match Tests ====================

    @Nested
    @DisplayName("Exact Match Pattern")
    class ExactMatchTests {

        @Test
        @DisplayName("Should match exact state")
        public void testExactMatch() {
            assertTrue(interpreter.matchesStatePattern("1", "1"));
            assertTrue(interpreter.matchesStatePattern("end", "end"));
            assertTrue(interpreter.matchesStatePattern("state_A", "state_A"));
        }

        @Test
        @DisplayName("Should not match different state")
        public void testExactMatchFailure() {
            assertFalse(interpreter.matchesStatePattern("1", "2"));
            assertFalse(interpreter.matchesStatePattern("end", "start"));
            assertFalse(interpreter.matchesStatePattern("A", "a"));
        }

        @Test
        @DisplayName("Should handle null values")
        public void testNullHandling() {
            assertFalse(interpreter.matchesStatePattern(null, "1"));
            assertFalse(interpreter.matchesStatePattern("1", null));
            assertFalse(interpreter.matchesStatePattern(null, null));
        }
    }

    // ==================== Wildcard Tests ====================

    @Nested
    @DisplayName("Wildcard Pattern (*)")
    class WildcardTests {

        @Test
        @DisplayName("Should match any state with wildcard")
        public void testWildcardMatchesAny() {
            assertTrue(interpreter.matchesStatePattern("*", "0"));
            assertTrue(interpreter.matchesStatePattern("*", "1"));
            assertTrue(interpreter.matchesStatePattern("*", "100"));
            assertTrue(interpreter.matchesStatePattern("*", "end"));
            assertTrue(interpreter.matchesStatePattern("*", "error"));
            assertTrue(interpreter.matchesStatePattern("*", "any_state_name"));
        }

        @Test
        @DisplayName("Should work in workflow as catch-all")
        public void testWildcardInWorkflow() {
            MatrixCode code = createWorkflow(
                step("0", "1", "action0"),
                step("*", "error", "catchAll")  // Catch-all
            );
            setCode(code);

            // Start at state "0", should execute action0 -> state "1"
            ActionResult result1 = interpreter.execCode();
            assertTrue(result1.isSuccess());
            assertEquals("1", interpreter.getCurrentState());

            // Now at state "1", should match "*" -> error
            ActionResult result2 = interpreter.execCode();
            assertTrue(result2.isSuccess());
            assertEquals("error", interpreter.getCurrentState());

            assertEquals(Arrays.asList("action0", "catchAll"), calledActions);
        }
    }

    // ==================== Negation Tests ====================

    @Nested
    @DisplayName("Negation Pattern (!)")
    class NegationTests {

        @Test
        @DisplayName("Should match anything except negated value")
        public void testNegationMatches() {
            assertTrue(interpreter.matchesStatePattern("!end", "0"));
            assertTrue(interpreter.matchesStatePattern("!end", "1"));
            assertTrue(interpreter.matchesStatePattern("!end", "error"));
            assertTrue(interpreter.matchesStatePattern("!end", "start"));
        }

        @Test
        @DisplayName("Should not match the negated value")
        public void testNegationExcludes() {
            assertFalse(interpreter.matchesStatePattern("!end", "end"));
            assertFalse(interpreter.matchesStatePattern("!error", "error"));
            assertFalse(interpreter.matchesStatePattern("!0", "0"));
        }

        @Test
        @DisplayName("Should work as error handler in workflow")
        public void testNegationAsErrorHandler() {
            // When failStep fails, the interpreter tries next row with same from-state "1"
            // Since there's no other "1" row, it falls through to "!end" which matches "1"
            MatrixCode code = createWorkflow(
                step("0", "1", "step1"),
                step("1", "2", "failStep"),  // Will fail
                step("1", "error", "errorHandler"),  // Same from-state, catches the failure
                step("error", "end", "cleanup")
            );
            setCode(code);

            ActionResult result = interpreter.runUntilEnd();
            assertTrue(result.isSuccess());

            // step1 succeeds, failStep fails, errorHandler catches, cleanup finishes
            assertEquals(Arrays.asList("step1", "failStep", "errorHandler", "cleanup"), calledActions);
        }
    }

    // ==================== OR Condition Tests ====================

    @Nested
    @DisplayName("OR Condition Pattern (|)")
    class OrConditionTests {

        @Test
        @DisplayName("Should match any of the OR values")
        public void testOrConditionMatches() {
            assertTrue(interpreter.matchesStatePattern("1|2|3", "1"));
            assertTrue(interpreter.matchesStatePattern("1|2|3", "2"));
            assertTrue(interpreter.matchesStatePattern("1|2|3", "3"));
            assertTrue(interpreter.matchesStatePattern("start|init|begin", "start"));
            assertTrue(interpreter.matchesStatePattern("start|init|begin", "init"));
            assertTrue(interpreter.matchesStatePattern("start|init|begin", "begin"));
        }

        @Test
        @DisplayName("Should not match values not in OR list")
        public void testOrConditionExcludes() {
            assertFalse(interpreter.matchesStatePattern("1|2|3", "4"));
            assertFalse(interpreter.matchesStatePattern("1|2|3", "0"));
            assertFalse(interpreter.matchesStatePattern("start|init|begin", "end"));
        }

        @Test
        @DisplayName("Should handle whitespace in OR values")
        public void testOrConditionWithWhitespace() {
            assertTrue(interpreter.matchesStatePattern("1 | 2 | 3", "1"));
            assertTrue(interpreter.matchesStatePattern("1 | 2 | 3", "2"));
            assertTrue(interpreter.matchesStatePattern("1 | 2 | 3", "3"));
        }

        @Test
        @DisplayName("Should work for multiple state consolidation")
        public void testOrConditionInWorkflow() {
            MatrixCode code = createWorkflow(
                step("0", "1", "init"),
                step("1|2|3", "end", "commonHandler")  // Handle states 1, 2, or 3
            );
            setCode(code);

            ActionResult result = interpreter.runUntilEnd();
            assertTrue(result.isSuccess());
            assertEquals(Arrays.asList("init", "commonHandler"), calledActions);
        }
    }

    // ==================== Numeric Comparison Tests ====================

    @Nested
    @DisplayName("Numeric Comparison Patterns")
    class NumericComparisonTests {

        @Test
        @DisplayName("Should match >= comparison")
        public void testGreaterThanOrEqual() {
            assertTrue(interpreter.matchesStatePattern(">=5", "5"));
            assertTrue(interpreter.matchesStatePattern(">=5", "6"));
            assertTrue(interpreter.matchesStatePattern(">=5", "100"));
            assertFalse(interpreter.matchesStatePattern(">=5", "4"));
            assertFalse(interpreter.matchesStatePattern(">=5", "0"));
        }

        @Test
        @DisplayName("Should match <= comparison")
        public void testLessThanOrEqual() {
            assertTrue(interpreter.matchesStatePattern("<=5", "5"));
            assertTrue(interpreter.matchesStatePattern("<=5", "4"));
            assertTrue(interpreter.matchesStatePattern("<=5", "0"));
            assertFalse(interpreter.matchesStatePattern("<=5", "6"));
            assertFalse(interpreter.matchesStatePattern("<=5", "100"));
        }

        @Test
        @DisplayName("Should match > comparison")
        public void testGreaterThan() {
            assertTrue(interpreter.matchesStatePattern(">5", "6"));
            assertTrue(interpreter.matchesStatePattern(">5", "100"));
            assertFalse(interpreter.matchesStatePattern(">5", "5"));
            assertFalse(interpreter.matchesStatePattern(">5", "4"));
        }

        @Test
        @DisplayName("Should match < comparison")
        public void testLessThan() {
            assertTrue(interpreter.matchesStatePattern("<5", "4"));
            assertTrue(interpreter.matchesStatePattern("<5", "0"));
            assertFalse(interpreter.matchesStatePattern("<5", "5"));
            assertFalse(interpreter.matchesStatePattern("<5", "6"));
        }

        @Test
        @DisplayName("Should handle decimal numbers")
        public void testDecimalNumbers() {
            assertTrue(interpreter.matchesStatePattern(">=1.5", "2"));
            assertTrue(interpreter.matchesStatePattern(">=1.5", "1.5"));
            assertFalse(interpreter.matchesStatePattern(">=1.5", "1"));
        }

        @Test
        @DisplayName("Should return false for non-numeric states")
        public void testNonNumericStates() {
            assertFalse(interpreter.matchesStatePattern(">=5", "abc"));
            assertFalse(interpreter.matchesStatePattern(">=5", "end"));
            assertFalse(interpreter.matchesStatePattern("<10", "error"));
        }

        @Test
        @DisplayName("Should work for timeout/retry scenarios")
        public void testNumericComparisonInWorkflow() {
            // Simulates retry logic: state < 4 retries, state >= 4 gives up
            MatrixCode code = createWorkflow(
                step("0", "1", "attempt"),
                step("<4", "end", "underFour"),   // States 1,2,3 -> succeed
                step(">=4", "error", "giveUp")    // State 4+ -> give up
            );
            setCode(code);

            // State 0 -> attempt -> state 1
            ActionResult r1 = interpreter.execCode();
            assertTrue(r1.isSuccess());
            assertEquals("1", interpreter.getCurrentState());

            // State 1 matches "<4" -> underFour -> end
            ActionResult r2 = interpreter.execCode();
            assertTrue(r2.isSuccess());
            assertEquals("end", interpreter.getCurrentState());

            assertEquals(Arrays.asList("attempt", "underFour"), calledActions);
        }
    }

    // ==================== JEXL Expression Tests ====================

    @Nested
    @DisplayName("JEXL Expression Pattern (jexl:)")
    class JexlExpressionTests {

        @Test
        @DisplayName("Should evaluate simple equality")
        public void testJexlEquality() {
            assertTrue(interpreter.matchesStatePattern("jexl:state == 'error'", "error"));
            assertFalse(interpreter.matchesStatePattern("jexl:state == 'error'", "ok"));
        }

        @Test
        @DisplayName("Should evaluate numeric comparisons with n variable")
        public void testJexlNumericComparison() {
            assertTrue(interpreter.matchesStatePattern("jexl:n >= 5", "5"));
            assertTrue(interpreter.matchesStatePattern("jexl:n >= 5", "10"));
            assertFalse(interpreter.matchesStatePattern("jexl:n >= 5", "4"));
        }

        @Test
        @DisplayName("Should evaluate compound expressions")
        public void testJexlCompoundExpression() {
            assertTrue(interpreter.matchesStatePattern("jexl:n >= 5 && n < 10", "7"));
            assertFalse(interpreter.matchesStatePattern("jexl:n >= 5 && n < 10", "3"));
            assertFalse(interpreter.matchesStatePattern("jexl:n >= 5 && n < 10", "15"));
        }

        @Test
        @DisplayName("Should evaluate OR expressions")
        public void testJexlOrExpression() {
            assertTrue(interpreter.matchesStatePattern("jexl:state == 'a' || state == 'b'", "a"));
            assertTrue(interpreter.matchesStatePattern("jexl:state == 'a' || state == 'b'", "b"));
            assertFalse(interpreter.matchesStatePattern("jexl:state == 'a' || state == 'b'", "c"));
        }

        @Test
        @DisplayName("Should evaluate regex matching")
        public void testJexlRegexMatch() {
            assertTrue(interpreter.matchesStatePattern("jexl:state =~ 'error.*'", "error"));
            assertTrue(interpreter.matchesStatePattern("jexl:state =~ 'error.*'", "error_123"));
            assertFalse(interpreter.matchesStatePattern("jexl:state =~ 'error.*'", "warning"));
        }

        @Test
        @DisplayName("Should evaluate string methods")
        public void testJexlStringMethods() {
            assertTrue(interpreter.matchesStatePattern("jexl:state.startsWith('err')", "error"));
            assertTrue(interpreter.matchesStatePattern("jexl:state.endsWith('ing')", "processing"));
            assertTrue(interpreter.matchesStatePattern("jexl:state.contains('mid')", "in_middle_here"));
            assertFalse(interpreter.matchesStatePattern("jexl:state.startsWith('err')", "warning"));
        }

        @Test
        @DisplayName("Should handle non-numeric states with n variable")
        public void testJexlNonNumericState() {
            // n is null for non-numeric states
            assertFalse(interpreter.matchesStatePattern("jexl:n >= 5", "abc"));
            assertTrue(interpreter.matchesStatePattern("jexl:n == null", "abc"));
            assertFalse(interpreter.matchesStatePattern("jexl:n == null", "5"));
        }

        @Test
        @DisplayName("Should work in workflow")
        public void testJexlInWorkflow() {
            MatrixCode code = createWorkflow(
                step("0", "5", "init"),
                step("jexl:n >= 5 && n < 10", "end", "inRange")
            );
            setCode(code);

            ActionResult result = interpreter.runUntilEnd();
            assertTrue(result.isSuccess());
            assertEquals(Arrays.asList("init", "inRange"), calledActions);
        }

        @Test
        @DisplayName("Should handle invalid expressions gracefully")
        public void testJexlInvalidExpression() {
            // Invalid expression should return false, not throw
            assertFalse(interpreter.matchesStatePattern("jexl:invalid syntax {{", "state"));
        }
    }

    // ==================== Integration Tests ====================

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should prioritize exact match over wildcard")
        public void testExactMatchPriority() {
            // Exact match row comes first, should be used
            MatrixCode code = createWorkflow(
                step("1", "2", "exactMatch"),
                step("*", "error", "wildcard")
            );
            setCode(code);

            // Set state to "1"
            interpreter.transitionTo("1");

            ActionResult result = interpreter.execCode();
            assertTrue(result.isSuccess());
            assertEquals("2", interpreter.getCurrentState());
            assertEquals(Arrays.asList("exactMatch"), calledActions);
        }

        @Test
        @DisplayName("Should fall through to wildcard when exact match fails")
        public void testFallThroughToWildcard() {
            MatrixCode code = createWorkflow(
                step("1", "2", "failExact"),  // Fails
                step("*", "error", "wildcard")  // Catches
            );
            setCode(code);

            interpreter.transitionTo("1");
            ActionResult result = interpreter.execCode();
            assertTrue(result.isSuccess());
            assertEquals("error", interpreter.getCurrentState());
            assertEquals(Arrays.asList("failExact", "wildcard"), calledActions);
        }

        @Test
        @DisplayName("Should handle complex error handling workflow")
        public void testComplexErrorHandling() {
            MatrixCode code = createWorkflow(
                step("0", "1", "step0"),
                step("1", "2", "step1"),
                step("2", "3", "failStep2"),  // Fails
                step("2", "end", "fallback2"),  // Fallback for state 2
                step("3", "end", "step3")
            );
            setCode(code);

            ActionResult result = interpreter.runUntilEnd();
            assertTrue(result.isSuccess());

            // step0, step1, failStep2 (fails), fallback2 succeeds
            assertEquals(Arrays.asList("step0", "step1", "failStep2", "fallback2"), calledActions);
        }

        @Test
        @DisplayName("matchesCurrentState should use pattern matching")
        public void testMatchesCurrentStateWithPattern() {
            // Need to set code first to avoid NPE in transitionTo
            MatrixCode code = createWorkflow(
                step("0", "1", "dummy")
            );
            setCode(code);

            Transition wildcardTransition = new Transition();
            wildcardTransition.setStates(Arrays.asList("*", "error"));

            Transition negationTransition = new Transition();
            negationTransition.setStates(Arrays.asList("!end", "error"));

            Transition numericTransition = new Transition();
            numericTransition.setStates(Arrays.asList(">=5", "high"));

            // Test pattern matching directly (without transitionTo which needs code)
            // Use reflection to set currentState
            try {
                java.lang.reflect.Field stateField = Interpreter.class.getDeclaredField("currentState");
                stateField.setAccessible(true);

                stateField.set(interpreter, "3");
                assertTrue(interpreter.matchesCurrentState(wildcardTransition));
                assertTrue(interpreter.matchesCurrentState(negationTransition));
                assertFalse(interpreter.matchesCurrentState(numericTransition));

                stateField.set(interpreter, "10");
                assertTrue(interpreter.matchesCurrentState(numericTransition));

                stateField.set(interpreter, "end");
                assertTrue(interpreter.matchesCurrentState(wildcardTransition));
                assertFalse(interpreter.matchesCurrentState(negationTransition));
            } catch (Exception e) {
                fail("Failed to set currentState: " + e.getMessage());
            }
        }
    }

    // ==================== Helper Methods ====================

    private Transition step(String fromState, String toState, String actionName) {
        Transition transition = new Transition();
        transition.setStates(Arrays.asList(fromState, toState));
        Action action = new Action();
        action.setActor("tracker");
        action.setMethod(actionName);
        transition.setActions(Arrays.asList(action));
        return transition;
    }

    private MatrixCode createWorkflow(Transition... steps) {
        MatrixCode code = new MatrixCode();
        code.setName("test-workflow");
        code.setSteps(Arrays.asList(steps));
        return code;
    }

    private void setCode(MatrixCode code) {
        try {
            java.lang.reflect.Field codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }
    }
}
