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

import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Verifies state S2.02: switch-based CallableByActionName dispatch pattern.
 */
@Tag("S_svc.02")
@DisplayName("SwitchDispatch — switch-based CallableByActionName dispatch (S2.02)")
public class SwitchDispatchTest {

    // -------------------------------------------------------------------------
    // Switch-based implementation (primary pattern — GraalVM compatible)
    // -------------------------------------------------------------------------

    static class MathActorSwitch implements CallableByActionName {
        private int lastResult = 0;

        public int add(int a, int b) { return lastResult = a + b; }
        public int multiply(int a, int b) { return lastResult = a * b; }
        public int getLastResult() { return lastResult; }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            try {
                switch (actionName) {
                    case "add": {
                        String[] p = args.split(",");
                        if (p.length != 2) return new ActionResult(false, "add requires a,b");
                        return new ActionResult(true,
                            String.valueOf(add(Integer.parseInt(p[0].trim()),
                                              Integer.parseInt(p[1].trim()))));
                    }
                    case "multiply": {
                        String[] p = args.split(",");
                        if (p.length != 2) return new ActionResult(false, "multiply requires a,b");
                        return new ActionResult(true,
                            String.valueOf(multiply(Integer.parseInt(p[0].trim()),
                                                   Integer.parseInt(p[1].trim()))));
                    }
                    case "getLastResult":
                        return new ActionResult(true, String.valueOf(getLastResult()));
                    default:
                        return new ActionResult(false,
                            "Unknown action: " + actionName);
                }
            } catch (NumberFormatException e) {
                return new ActionResult(false, "Invalid number format: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Actor covering all 3 args patterns
    // -------------------------------------------------------------------------

    static class MultiArgActor implements CallableByActionName {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            try {
                switch (actionName) {
                    // ① plain string
                    case "greet":
                        return new ActionResult(true, "Hello, " + args);
                    // ② comma-separated
                    case "add": {
                        String[] p = args.split(",");
                        if (p.length != 2) return new ActionResult(false, "add requires a,b");
                        int sum = Integer.parseInt(p[0].trim()) + Integer.parseInt(p[1].trim());
                        return new ActionResult(true, String.valueOf(sum));
                    }
                    // ③ JSON
                    case "processData": {
                        JsonNode json = mapper.readTree(args);
                        String input  = json.get("input").asText();
                        String output = json.get("output").asText();
                        return new ActionResult(true, input + "->" + output);
                    }
                    default:
                        return new ActionResult(false, "Unknown action: " + actionName);
                }
            } catch (Exception e) {
                return new ActionResult(false, "Error: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tests — args の 3 つの受け取り方
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("args の 3 つの受け取り方")
    class ArgsFormats {

        private MultiArgActor actor;

        @BeforeEach
        void setUp() { actor = new MultiArgActor(); }

        @Test
        @DisplayName("① プレーン文字列: args をそのまま使う")
        void plainString() {
            ActionResult r = actor.callByActionName("greet", "World");
            assertTrue(r.isSuccess());
            assertEquals("Hello, World", r.getResult());
        }

        @Test
        @DisplayName("① プレーン文字列: 空文字列も受け取れる")
        void plainStringEmpty() {
            ActionResult r = actor.callByActionName("greet", "");
            assertTrue(r.isSuccess());
            assertEquals("Hello, ", r.getResult());
        }

        @Test
        @DisplayName("② カンマ区切り: 複数の単純な値を渡す")
        void commaSeparated() {
            ActionResult r = actor.callByActionName("add", "5,3");
            assertTrue(r.isSuccess());
            assertEquals("8", r.getResult());
        }

        @Test
        @DisplayName("② カンマ区切り: 引数不足はエラー")
        void commaSeparatedWrongCount() {
            ActionResult r = actor.callByActionName("add", "5");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("③ JSON: 構造化データを渡す")
        void jsonArgs() {
            ActionResult r = actor.callByActionName("processData",
                    "{\"input\":\"src\",\"output\":\"dst\"}");
            assertTrue(r.isSuccess());
            assertEquals("src->dst", r.getResult());
        }

        @Test
        @DisplayName("③ JSON: 不正な JSON はエラー")
        void jsonArgsMalformed() {
            ActionResult r = actor.callByActionName("processData", "not-json");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("同一アクター内で 3 パターンを混在できる")
        void allThreePatternsCoexist() {
            assertTrue(actor.callByActionName("greet", "Alice").isSuccess());
            assertTrue(actor.callByActionName("add", "2,3").isSuccess());
            assertTrue(actor.callByActionName("processData",
                    "{\"input\":\"a\",\"output\":\"b\"}").isSuccess());
        }
    }

    // -------------------------------------------------------------------------
    // Tests — Switch pattern
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Switch-based dispatch")
    class SwitchBased {

        private MathActorSwitch actor;

        @BeforeEach
        void setUp() { actor = new MathActorSwitch(); }

        @Test
        @DisplayName("add: valid args return correct result")
        void addReturnsCorrectResult() {
            ActionResult r = actor.callByActionName("add", "5,3");
            assertTrue(r.isSuccess());
            assertEquals("8", r.getResult());
        }

        @Test
        @DisplayName("multiply: valid args return correct result")
        void multiplyReturnsCorrectResult() {
            ActionResult r = actor.callByActionName("multiply", "4,2");
            assertTrue(r.isSuccess());
            assertEquals("8", r.getResult());
        }

        @Test
        @DisplayName("getLastResult: returns result of previous operation")
        void getLastResultReturnsPreviousResult() {
            actor.callByActionName("add", "10,5");
            ActionResult r = actor.callByActionName("getLastResult", "");
            assertTrue(r.isSuccess());
            assertEquals("15", r.getResult());
        }

        @Test
        @DisplayName("unknown action name returns success=false")
        void unknownActionReturnsFalse() {
            ActionResult r = actor.callByActionName("divide", "10,2");
            assertFalse(r.isSuccess());
            assertTrue(r.getResult().contains("divide"));
        }

        @Test
        @DisplayName("invalid number format returns success=false")
        void invalidNumberFormatReturnsFalse() {
            ActionResult r = actor.callByActionName("add", "five,three");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("wrong arg count returns success=false")
        void wrongArgCountReturnsFalse() {
            ActionResult r = actor.callByActionName("add", "5");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("type-safe direct call and string-based call coexist")
        void typeSafeAndStringBasedCoexist() {
            int direct = actor.add(3, 4);
            assertEquals(7, direct);

            ActionResult stringBased = actor.callByActionName("add", "3,4");
            assertTrue(stringBased.isSuccess());
            assertEquals("7", stringBased.getResult());
        }
    }

    // -------------------------------------------------------------------------
    // Tests — ActorRef integration
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ActorRef integration")
    class WithActorRef {

        private ActorSystem system;

        @BeforeEach
        void setUp() { system = new ActorSystem("test"); }

        @AfterEach
        void tearDown() { system.terminate(); }

        @Test
        @DisplayName("switch actor processes callByActionName via tell()")
        void switchActorViaActorRef() throws Exception {
            ActorRef<MathActorSwitch> math = system.actorOf("math", new MathActorSwitch());

            ActionResult result = math.ask(m -> m.callByActionName("add", "5,3"))
                                      .get(3, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("8", result.getResult());
        }

        @Test
        @DisplayName("message ordering is preserved for sequential callByActionName calls")
        void messageOrderingPreserved() throws Exception {
            ActorRef<MathActorSwitch> math = system.actorOf("math", new MathActorSwitch());

            math.tell(m -> m.callByActionName("add", "10,5"));
            math.tell(m -> m.callByActionName("multiply", "3,2"));

            ActionResult last = math.ask(m -> m.callByActionName("getLastResult", ""))
                                    .get(3, TimeUnit.SECONDS);

            assertTrue(last.isSuccess());
            assertEquals("6", last.getResult()); // multiply(3,2)=6 was last
        }
    }
}
