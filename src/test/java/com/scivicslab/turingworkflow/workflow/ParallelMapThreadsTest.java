/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Which threads {@code parallel-map run} uses, how many items it has in flight, and what an item can
 * reach. Every item's work runs on the actor system's one ManagedThreadPool; no other real thread is
 * made. Each test runs under a time limit, so a hang fails the test instead of the build.
 */
@DisplayName("parallel-map - one ManagedThreadPool, bounded in-flight items, isolated items")
class ParallelMapThreadsTest {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /** Records, for every call, the thread it ran on and how many calls were inside at once. */
    public static final class Probe extends IIActorRef<Object> {
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();

        public Probe(String name, IIActorSystem system) {
            super(name, new Object(), system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            threads.add(Thread.currentThread());
            mostAtOnce.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
                return new ActionResult(true, "probed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ActionResult.error(e, getName() + "." + actionName);
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    /** Calls the probe, then answers the item. */
    private static final String PROBE_YAML = """
            name: probe-item
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: probe
                    method: touch
              - states: ["1", "end"]
                actions:
                  - actor: this
                    method: putJson
                    arguments: {path: output, value: "jexl: state.getString('input')"}
            """;

    /** The thread the last {@link #runOver} ran {@code run} on, which is the thread that waited. */
    private final java.util.concurrent.atomic.AtomicReference<Thread> waiter =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Calls {@code parallel-map run} on the actor's mailbox virtual thread, as the approved design has
     * it: the mailbox virtual thread waits, the items run on the pool's real threads.
     */
    private ActionResult runOver(IIActorSystem system, Path yaml, JSONArray items, Integer maxParallel)
            throws Exception {
        JSONObject args = new JSONObject().put("subWorkflow", yaml.toAbsolutePath().toString()).put("items", items);
        if (maxParallel != null) {
            args.put("maxParallel", maxParallel);
        }
        IIActorRef<Object> pm = system.getIIActor("parallel-map");
        return pm.ask(o -> {
            waiter.set(Thread.currentThread());
            return pm.callByActionName("run", args.toString());
        }).get();
    }

    private static JSONArray letters(int n) {
        JSONArray items = new JSONArray();
        for (int i = 0; i < n; i++) {
            items.put(String.valueOf((char) ('a' + i)));
        }
        return items;
    }

    /** The live real (platform) threads of the JVM. */
    private static int liveRealThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isVirtual()).count();
    }

    @Test
    @DisplayName("nested 3 levels x 5 items: live real threads stay within the pool size plus a few")
    void nestedParallelMapKeepsRealThreadsBounded(@TempDir Path dir) throws Exception {
        int poolSize = 4;
        Path leaf = dir.resolve("leaf.yaml");
        Files.writeString(leaf, """
                name: leaf
                steps:
                  - states: ["0", "1"]
                    actions:
                      - actor: this
                        method: sleep
                        arguments: "20"
                  - states: ["1", "end"]
                    actions:
                      - actor: this
                        method: putJson
                        arguments: {path: output, value: "jexl: state.getString('input')"}
                """);
        Path middle = dir.resolve("middle.yaml");
        Files.writeString(middle, mapStep("middle", leaf));
        Path top = dir.resolve("top.yaml");
        Files.writeString(top, mapStep("top", middle));

        system = new IIActorSystem("pm-nested", poolSize);
        int before = liveRealThreads();
        AtomicInteger most = new AtomicInteger(before);
        AtomicBoolean done = new AtomicBoolean();
        // The sampler is a virtual thread, so it adds no real thread to what it counts.
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                most.accumulateAndGet(liveRealThreads(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, top, letters(5), 5));
        done.set(true);
        sampler.join();

        System.out.println("[t271] live real threads: before=" + before + " most during the run=" + most.get()
                + " pool size=" + poolSize);
        assertTrue(r.isSuccess(), r.getResult());
        assertEquals(5, new JSONArray(r.getResult()).length());
        // The pool's real threads are made lazily, so all of them may appear during the run; a few more
        // are allowed for JVM housekeeping.
        assertTrue(most.get() <= before + poolSize + 4,
                "real threads grew from " + before + " to " + most.get() + " with a pool of " + poolSize);
    }

    /** A sub-workflow whose one step maps {@code sub} over five items and keeps the answers as output. */
    private static String mapStep(String name, Path sub) {
        return """
                name: %s
                steps:
                  - states: ["0", "1"]
                    actions:
                      - actor: parallel-map
                        method: run
                        arguments:
                          subWorkflow: "%s"
                          items: ["a", "b", "c", "d", "e"]
                          maxParallel: 5
                  - states: ["1", "end"]
                    actions:
                      - actor: this
                        method: putJson
                        arguments: {path: output, value: "jexl: result"}
                """.formatted(name, sub.toAbsolutePath());
    }

    @Test
    @DisplayName("one ManagedThreadPool real thread, one parallel-map from a workflow step: it completes with the right answers")
    void onePoolRealThread(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("probe.yaml");
        Files.writeString(yaml, PROBE_YAML);
        system = new IIActorSystem("pm-one-thread", 1);
        Probe probe = new Probe("probe", system);
        system.addIIActor(probe);
        Interpreter interpreter = new Interpreter.Builder().loggerName("main").team(system).build();
        InterpreterIIAR main = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(main);
        system.addIIActor(main);
        interpreter.readYaml(new ByteArrayInputStream("""
                name: map-on-one-thread
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: parallel-map
                        method: run
                        arguments:
                          subWorkflow: "%s"
                          items: ["a", "b", "c"]
                """.formatted(yaml.toAbsolutePath()).getBytes(StandardCharsets.UTF_8)));

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> interpreter.runUntilEnd(10));

        assertTrue(r.isSuccess(), r.getResult());
        assertEquals("[\"a\",\"b\",\"c\"]", main.getLastResult().getResult());
        assertEquals(3, probe.threads.size());
        // Behaviour only: which thread runs each item on this fallback path is not asserted, as the
        // inline rule of ask(action, pool) may change (ask through the mailbox first). Printed for the
        // record.
        System.out.println("[t271] plain YAML step: items ran on " + Set.copyOf(probe.threads));
    }

    @Test
    @DisplayName("maxParallel 2 over 6 items: at most 2 items in flight, on pool real threads")
    void maxParallelCapsItemsInFlight(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("probe.yaml");
        Files.writeString(yaml, PROBE_YAML);
        system = new IIActorSystem("pm-two-at-once", 4);
        Probe probe = new Probe("probe", system);
        system.addIIActor(probe);

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, yaml, letters(6), 2));

        assertTrue(r.isSuccess(), r.getResult());
        assertEquals("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]", r.getResult());
        assertEquals(6, probe.threads.size());
        assertEquals(2, probe.mostAtOnce.get(), "two at once, never more");
        assertTrue(waiter.get().isVirtual(), "run waited on " + waiter.get());
        for (Thread t : probe.threads) {
            assertFalse(t.isVirtual(), t.toString());
            assertTrue(t.getName().startsWith("managed-pool-"), t.getName());
        }
        assertTrue(system.listActorNames().stream().noneMatch(n -> n.contains(".item-")),
                "no item outlives the run: " + system.listActorNames());
    }

    @Test
    @DisplayName("maxParallel above the limit, or below 1, is refused")
    void maxParallelOverTheLimitIsRefused(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("probe.yaml");
        Files.writeString(yaml, PROBE_YAML);
        system = new IIActorSystem("pm-limit", 2);
        Probe probe = new Probe("probe", system);
        system.addIIActor(probe);

        ActionResult over = runOver(system, yaml, letters(2), ParallelMapActor.MAX_PARALLEL_LIMIT + 1);
        ActionResult zero = runOver(system, yaml, letters(2), 0);

        assertFalse(over.isSuccess(), over.getResult());
        assertEquals("error: parallel-map.run: maxParallel 257 is outside 1..256", over.getResult());
        assertFalse(zero.isSuccess(), zero.getResult());
        assertEquals(0, probe.threads.size(), "nothing ran");
        assertTrue(runOver(system, yaml, letters(2), ParallelMapActor.MAX_PARALLEL_LIMIT).isSuccess());
    }

    @Test
    @DisplayName("an item has no loader: using it fails, and no actor is made")
    void itemHasNoLoader(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("load.yaml");
        Files.writeString(yaml, """
                name: try-the-loader
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: loader
                        method: createChild
                        arguments: ["ROOT", "smuggled", "com.scivicslab.turingworkflow.workflow.ParallelMapThreadsTest$Probe"]
                """);
        system = new IIActorSystem("pm-no-loader", 2);
        // The calling workflow has a loader; its items must not reach it.
        system.addIIActor(new DynamicActorLoaderIIAR("loader", system));

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, yaml, letters(1), 1));

        assertFalse(r.isSuccess(), r.getResult());
        assertTrue(r.getResult().startsWith("1 of 1 failed: 0 (a): error: sub-workflow failed: "), r.getResult());
        assertTrue(r.getResult().contains("Actor not found: loader"), r.getResult());
        assertNull(system.getActor("smuggled"));
    }

    @Test
    @DisplayName("items are isolated: one item's state writes are seen by no other item, nor by the caller")
    void itemsAreIsolated(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("write.yaml");
        Files.writeString(yaml, """
                name: write-and-read
                steps:
                  - states: ["0", "1"]
                    actions:
                      - actor: this
                        method: appendJson
                        arguments: {path: seen, value: "jexl: state.getString('input')"}
                      - actor: this
                        method: sleep
                        arguments: "30"
                  - states: ["1", "2"]
                    note: "try to write where the others could read it: the calling workflow and the siblings"
                    actions:
                      - actor: main
                        method: appendJson
                        arguments: {path: leaked, value: "jexl: state.getString('input')"}
                  - states: ["1", "2"]
                    actions:
                      - actor: this
                        method: doNothing
                  - states: ["2", "3"]
                    actions:
                      - actor: "../*"
                        method: appendJson
                        arguments: {path: seen, value: "jexl: 'from ' + state.getString('input')"}
                  - states: ["3", "end"]
                    actions:
                      - actor: this
                        method: putJson
                        arguments: {path: output, value: "jexl: state.select('seen').size()"}
                """);
        system = new IIActorSystem("pm-isolated", 4);
        Interpreter interpreter = new Interpreter.Builder().loggerName("main").team(system).build();
        InterpreterIIAR main = new InterpreterIIAR("main", interpreter, system);
        interpreter.setSelfActorRef(main);
        system.addIIActor(main);

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, yaml, letters(4), 4));

        assertTrue(r.isSuccess(), r.getResult());
        // Each item saw its own input and its own write through ../* (itself), nothing from the others.
        assertEquals("[2,2,2,2]", r.getResult());
        assertTrue(main.json().select("leaked").isMissingNode(), "the caller's state: " + main.json());
    }

    /** Tries {@code attempt}; when that step fails, says so; then answers {@code answer} as output. */
    private static String tryThenAnswer(String attempt, String answer) {
        return """
                name: try-then-answer
                steps:
                  - states: ["0", "1"]
                    actions:
                      - actor: this
                        method: sleep
                        arguments: "100"
                  - states: ["1", "2"]
                    actions:
                %s
                  - states: ["1", "2"]
                    actions:
                      - actor: this
                        method: putJson
                        arguments: {path: refused, value: true}
                  - states: ["2", "3"]
                    actions:
                      - actor: this
                        method: sleep
                        arguments: "100"
                  - states: ["3", "end"]
                    actions:
                      - actor: this
                        method: putJson
                        arguments: {path: output, value: "%s"}
                """.formatted(attempt.indent(6).stripTrailing(), answer);
    }

    @Test
    @DisplayName("an item cannot reach a sibling item")
    void itemCannotReachSibling(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("sibling.yaml");
        Files.writeString(yaml, tryThenAnswer("""
                - actor: this
                  method: onlyIf
                  arguments: "jexl: state.getString('input') == 'a'"
                - actor: "../*item-1"
                  method: putJson
                  arguments: {path: poke, value: "jexl: 'from ' + state.getString('input')"}""",
                "jexl: state.getString('input') + ':' + state.select('poke').isMissingNode()"));
        system = new IIActorSystem("pm-sibling", 2);

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, yaml, letters(2), 2));

        assertTrue(r.isSuccess(), r.getResult());
        // Both items are alive at once (each sleeps); item a's write into item b found nothing.
        assertEquals("[\"a:true\",\"b:true\"]", r.getResult());
    }

    @Test
    @DisplayName("an item cannot reach the calling workflow's vars")
    void itemCannotReachVars(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("vars.yaml");
        Files.writeString(yaml, tryThenAnswer("""
                - actor: vars
                  method: get
                  arguments: "secret"
                - actor: this
                  method: putJson
                  arguments: {path: got, value: "jexl: result"}""",
                "jexl: state.select('refused').asBoolean() + ':' + state.select('got').isMissingNode()"));
        system = new IIActorSystem("pm-vars", 2);
        system.addIIActor(new VarsActor(system, java.util.Map.of("secret", "s3cret")));

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, yaml, letters(2), 2));

        assertTrue(r.isSuccess(), r.getResult());
        assertFalse(r.getResult().contains("s3cret"), r.getResult());
        assertEquals("[\"true:true\",\"true:true\"]", r.getResult());
    }

    @Test
    @DisplayName("an item cannot reach a loader registered under another name")
    void itemCannotReachRenamedLoader(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("renamed-loader.yaml");
        Files.writeString(yaml, """
                name: try-the-factory
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: factory
                        method: createChild
                        arguments: ["ROOT", "smuggled", "com.scivicslab.turingworkflow.workflow.ParallelMapThreadsTest$Probe"]
                """);
        system = new IIActorSystem("pm-renamed-loader", 2);
        system.addIIActor(new DynamicActorLoaderIIAR("factory", system));

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> runOver(system, yaml, letters(1), 1));

        assertFalse(r.isSuccess(), r.getResult());
        assertTrue(r.getResult().contains("Actor not found: factory"), r.getResult());
        assertNull(system.getActor("smuggled"));
    }
}
