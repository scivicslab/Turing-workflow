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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Verifies state S2.01: CallableByActionName interface contract.
 *
 * Tests the interface itself — not implementation patterns (switch/annotation).
 * Those are covered in SwitchDispatchTest (S2.02) and ActionDispatcherTest (S2.04).
 */
@Tag("S_svc.01")
@DisplayName("CallableByActionName interface contract (S2.01)")
public class CallableByActionNameTest {

    // -------------------------------------------------------------------------
    // Minimal implementation — only used in these tests
    // -------------------------------------------------------------------------

    static class CounterActor implements CallableByActionName {
        private int count = 0;

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            switch (actionName) {
                case "increment": count++; return new ActionResult(true, String.valueOf(count));
                case "decrement": count--; return new ActionResult(true, String.valueOf(count));
                case "getCount":  return new ActionResult(true, String.valueOf(count));
                case "reset":     count = 0; return new ActionResult(true, "0");
                default:          return new ActionResult(false, "Unknown action: " + actionName);
            }
        }
    }

    static class EchoActor implements CallableByActionName {
        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if ("echo".equals(actionName)) return new ActionResult(true, args);
            return new ActionResult(false, "Unknown action: " + actionName);
        }
    }

    // -------------------------------------------------------------------------
    // Interface contract
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Interface contract")
    class InterfaceContract {

        @Test
        @DisplayName("callByActionName returns ActionResult — not null")
        void returnsActionResult() {
            CallableByActionName actor = new CounterActor();
            ActionResult r = actor.callByActionName("increment", "");
            assertNotNull(r);
        }

        @Test
        @DisplayName("known action returns success=true")
        void knownActionReturnsSuccess() {
            CallableByActionName actor = new CounterActor();
            ActionResult r = actor.callByActionName("increment", "");
            assertTrue(r.isSuccess());
        }

        @Test
        @DisplayName("unknown action returns success=false")
        void unknownActionReturnsFalse() {
            CallableByActionName actor = new CounterActor();
            ActionResult r = actor.callByActionName("nonexistent", "");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("error result carries a non-null message")
        void errorResultHasMessage() {
            CallableByActionName actor = new CounterActor();
            ActionResult r = actor.callByActionName("nonexistent", "");
            assertNotNull(r.getResult());
            assertFalse(r.getResult().isBlank());
        }

        @Test
        @DisplayName("args string is passed through to the implementation")
        void argsPassedThrough() {
            CallableByActionName actor = new EchoActor();
            ActionResult r = actor.callByActionName("echo", "hello world");
            assertTrue(r.isSuccess());
            assertEquals("hello world", r.getResult());
        }

        @Test
        @DisplayName("empty args string is accepted")
        void emptyArgsAccepted() {
            CallableByActionName actor = new CounterActor();
            ActionResult r = actor.callByActionName("getCount", "");
            assertTrue(r.isSuccess());
        }
    }

    // -------------------------------------------------------------------------
    // Type-unknown caller — the core value proposition
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Type-unknown caller")
    class TypeUnknownCaller {

        @Test
        @DisplayName("Object reference can be cast to CallableByActionName and called without knowing the concrete type")
        void objectCanBeCastAndCalled() {
            // Simulates a dynamically loaded actor: concrete type is erased to Object
            Object loaded = new CounterActor();

            // The caller never references CounterActor — only the common interface
            CallableByActionName actor = (CallableByActionName) loaded;
            ActionResult r = actor.callByActionName("increment", "");
            assertTrue(r.isSuccess());
            assertEquals("1", r.getResult());
        }

        @Test
        @DisplayName("ActorRef<?> with erased type can call via CallableByActionName cast")
        void wildcardActorRefCanCallViaInterface() throws Exception {
            ActorSystem system = new ActorSystem("test");
            try {
                // ActorRef<?> simulates a plugin actor whose concrete type is not known at compile time
                ActorRef<?> ref = system.actorOf("plugin", new CounterActor());

                ActionResult r = ref.ask(a -> ((CallableByActionName) a).callByActionName("increment", ""))
                                    .get(3, TimeUnit.SECONDS);
                assertTrue(r.isSuccess());
                assertEquals("1", r.getResult());
            } finally {
                system.terminate();
            }
        }

        @Test
        @DisplayName("identical caller code works regardless of which implementation is behind the interface")
        void sameCallerCodeForAnyImplementation() {
            Object[] plugins = { new CounterActor(), new EchoActor() };
            String[] actions = { "increment",        "echo" };
            String[] args    = { "",                 "hello" };

            for (int i = 0; i < plugins.length; i++) {
                CallableByActionName actor = (CallableByActionName) plugins[i];
                assertTrue(actor.callByActionName(actions[i], args[i]).isSuccess());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Polymorphism — multiple implementations behind the same interface type
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Polymorphism")
    class Polymorphism {

        @Test
        @DisplayName("different implementations callable through the same interface type")
        void differentImplementationsShareInterface() {
            CallableByActionName[] actors = {
                new CounterActor(),
                new EchoActor()
            };

            // CounterActor responds to "increment"
            assertTrue(actors[0].callByActionName("increment", "").isSuccess());
            // EchoActor does not
            assertFalse(actors[1].callByActionName("increment", "").isSuccess());

            // EchoActor responds to "echo"
            assertTrue(actors[1].callByActionName("echo", "hi").isSuccess());
            // CounterActor does not
            assertFalse(actors[0].callByActionName("echo", "hi").isSuccess());
        }

        @Test
        @DisplayName("state is local to each instance")
        void stateIsLocalToInstance() {
            CounterActor a1 = new CounterActor();
            CounterActor a2 = new CounterActor();

            a1.callByActionName("increment", "");
            a1.callByActionName("increment", "");
            a1.callByActionName("increment", "");

            assertEquals("3", a1.callByActionName("getCount", "").getResult());
            assertEquals("0", a2.callByActionName("getCount", "").getResult());
        }
    }

    // -------------------------------------------------------------------------
    // ActorRef integration — type-safe lambda and string-based calls coexist
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
        @DisplayName("callByActionName dispatches correctly through ActorRef.ask()")
        void callByActionNameViaAsk() throws Exception {
            ActorRef<CounterActor> ref = system.actorOf("counter", new CounterActor());

            ActionResult r = ref.ask(a -> a.callByActionName("increment", ""))
                                .get(3, TimeUnit.SECONDS);

            assertTrue(r.isSuccess());
            assertEquals("1", r.getResult());
        }

        @Test
        @DisplayName("type-safe lambda and string-based calls coexist on the same ActorRef")
        void typeSafeAndStringBasedCoexist() throws Exception {
            ActorRef<CounterActor> ref = system.actorOf("counter", new CounterActor());

            // type-safe direct call
            ref.tell(a -> a.callByActionName("increment", ""));
            ref.tell(a -> a.callByActionName("increment", ""));

            // string-based call
            ActionResult r = ref.ask(a -> a.callByActionName("getCount", ""))
                                .get(3, TimeUnit.SECONDS);

            assertTrue(r.isSuccess());
            assertEquals("2", r.getResult());
        }

        @Test
        @DisplayName("unknown action returns failure without throwing even through ActorRef")
        void unknownActionDoesNotThrow() throws Exception {
            ActorRef<CounterActor> ref = system.actorOf("counter", new CounterActor());

            ActionResult r = ref.ask(a -> a.callByActionName("fly", ""))
                                .get(3, TimeUnit.SECONDS);

            assertFalse(r.isSuccess());
            assertFalse(r.getResult().isBlank());
        }
    }
}
