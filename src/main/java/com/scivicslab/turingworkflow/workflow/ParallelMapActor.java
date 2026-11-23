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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in parallel map-over-subworkflow actor. Auto-created on first use as {@code parallel-map}.
 *
 * <p>This is the missing "parallel" primitive of the engine's control vocabulary (sequence / branch /
 * loop / sub-workflow via {@code this}). Where {@code this.call} runs ONE sub-workflow, {@code parallel-map}
 * runs a sub-workflow once per item of a list, concurrently, and answers the outputs.</p>
 *
 * <h2>Action</h2>
 * <ul>
 *   <li>{@code run} — arguments {@code {subWorkflow, items, maxParallel?, baseDir?}}. {@code items} is a JSON
 *       array, usually read from the workflow's own state ({@code items: "jexl: state.select('xs')"}). For each
 *       item, runs the sub-workflow in its OWN isolated child {@link IIActorSystem} so per-task state — and the
 *       per-task {@code llm} actor — never collide and execution is genuinely parallel. The item is put in the
 *       sub-workflow's own state at {@code input}; the sub-workflow leaves its answer in its state at
 *       {@code output}. When every item succeeded, the run is true and its result is the JSON array of the
 *       answers in item order, which the workflow stores with {@code this.putJson {path: ys, value: "jexl:
 *       result"}}. Otherwise it is false with {@code "F of T failed: <index> (<item>): error: ...; ... .
 *       parallel-map: mapped T item(s)"}, following {@code Interpreter.apply}, and the workflow chooses what to do
 *       with it. A fatal error from an item's run ({@link ActionResult#fatalIn}) is not one item's failure: it is
 *       rethrown, so the calling workflow records it and ends.</li>
 * </ul>
 *
 * <p>The sub-workflow must be self-contained (load any plugin it needs via {@code loader} — e.g.
 * {@code plugin-llm}), because each task runs in its own isolated system that shares nothing with the parent.</p>
 */
public class ParallelMapActor extends IIActorRef<Object> {

    private static final Logger LOG = Logger.getLogger(ParallelMapActor.class.getName());
    private static final int DEFAULT_PARALLEL = 6;
    private static final int SUB_MAX_ITERATIONS = 100_000;

    private final IIActorSystem parentSystem;

    public ParallelMapActor(String name, IIActorSystem system) {
        super(name, new Object(), system);
        this.parentSystem = system;
    }

    /**
     * Which sub-workflow to run over which items.
     *
     * @param subWorkflow the sub-workflow's YAML file name
     * @param items       the items, one sub-workflow run each; each is put in the sub-workflow's state at
     *                    {@code input}
     * @param maxParallel how many items run at once; six when absent
     * @param baseDir     the directory the sub-workflow runs in; the current directory when absent
     */
    public record MapArgs(@NotNull String subWorkflow, @NotNull List<Object> items,
                          Integer maxParallel, String baseDir) {}

    @Action(value = "run", argsType = MapArgs.class)
    public ActionResult run(MapArgs args) {
        final String subYaml = args.subWorkflow();
        final int maxParallel = args.maxParallel() != null ? args.maxParallel() : DEFAULT_PARALLEL;
        final String baseDir = args.baseDir() != null ? args.baseDir() : ".";
        final List<Object> items = args.items();

        // Map: run the sub-workflow per item in parallel, each in its own isolated child system.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, maxParallel));
        List<Future<ItemRun>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Object item = items.get(i);
            futures.add(pool.submit(() -> runOne(subYaml, baseDir, item, idx)));
        }
        List<ItemRun> outputs = new ArrayList<>(items.size());
        try {
            for (Future<ItemRun> f : futures) {
                // No time limit on purpose: a sub-workflow run may legitimately take a long time,
                // and each run's future always completes. Following TimeoutAndScheduler_251012_oo01,
                // a caller that needs a limit puts it on its own wait.
                outputs.add(f.get());
            }
        } catch (Exception e) {
            pool.shutdownNow();
            // A fatal error from an item's run is not one item's failure: the JVM is failing, so it
            // goes on out of this action, and the calling workflow records it and ends.
            ActionResult.rethrowIfFatal(e);
            return ActionResult.error(e, "parallel-map.run, a sub-workflow run");
        } finally {
            pool.shutdown();
        }

        String summary = "parallel-map: mapped " + items.size() + " item(s)";
        List<String> failures = new ArrayList<>();
        JSONArray answers = new JSONArray();
        for (int i = 0; i < outputs.size(); i++) {
            ItemRun run = outputs.get(i);
            if (run.failed()) {
                failures.add(i + " (" + items.get(i) + "): " + run.output());
            }
            answers.put(run.output() == null ? JSONObject.NULL : run.output());
        }
        if (failures.isEmpty()) {
            // The answers in item order, for the workflow to store with this.putJson.
            return new ActionResult(true, answers.toString());
        }
        return new ActionResult(false, failures.size() + " of " + items.size() + " failed: " + String.join("; ", failures) + ". " + summary);
    }

    /**
     * Runs the sub-workflow for one item in an isolated child system; returns the {@code output} it
     * left in its state. A fatal error is rethrown rather than made one item's failure.
     */
    private ItemRun runOne(String subYaml, String baseDir, Object item, int idx) {
        IIActorSystem sub = new IIActorSystem("pm-" + getName() + "-" + idx, 2);
        // The child system is isolated from the parent's actors, not from the parent's limit on expressions.
        sub.setExpressionAllowListEnabled(parentSystem.isExpressionAllowListEnabled());
        try {
            Interpreter interp = new Interpreter.Builder().loggerName("pm-" + idx).team(sub).build();
            interp.setWorkflowBaseDir(baseDir);
            InterpreterIIAR self = new InterpreterIIAR("interpreter", interp, sub);
            interp.setSelfActorRef(self);
            sub.addIIActor(self);
            // Equip the isolated system with the standard helpers the sub-workflow may use.
            sub.addIIActor(new DynamicActorLoaderIIAR("loader", sub));
            sub.addIIActor(new VarsActor(sub, new HashMap<>()));

            // Put the item in the sub-workflow's own state, run it, read what it left at output.
            ActionResult seeded = self.callByActionName("putJson",
                    new JSONObject().put("path", INPUT_PATH).put("value", item).toString());
            if (!seeded.isSuccess()) {
                return ItemRun.failed(seeded.getResult());
            }
            ActionResult r = interp.runWorkflow(subYaml, SUB_MAX_ITERATIONS);
            if (!r.isSuccess()) {
                return ItemRun.failed("error: sub-workflow failed: " + r.getResult());
            }
            com.fasterxml.jackson.databind.JsonNode output = self.json().select(OUTPUT_PATH);
            if (output.isMissingNode() || output.isNull()) {
                return ItemRun.done(null);
            }
            return ItemRun.done(output.isTextual() ? output.asText()
                    : new org.json.JSONTokener(output.toString()).nextValue());
        } catch (Throwable t) {
            ActionResult.rethrowIfFatal(t);
            LOG.log(Level.WARNING, "parallel-map task " + idx + " failed", t);
            return ItemRun.failed(ActionResult.errorText(t, "parallel-map item " + idx));
        } finally {
            // Shut down BOTH the actors (close hooks) AND the isolated system's thread pool. Without
            // terminate(), each task leaks a non-daemon executor: harmless when the host exits via
            // System.exit (CLI), but a thread leak in a long-running host (e.g. the chat-ui3 server)
            // and enough to keep a plain JVM from exiting.
            try { sub.terminateIIActors(); } catch (Exception ignore) { /* best effort */ }
            try { sub.terminate(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    /** Where in the sub-workflow's own state the item is put. */
    static final String INPUT_PATH = "input";

    /** Where in the sub-workflow's own state its answer is read. */
    static final String OUTPUT_PATH = "output";

    /**
     * What one item's sub-workflow run left: its answer, and whether it failed (then the answer is the
     * {@code error: ...} text).
     */
    private record ItemRun(Object output, boolean failed) {
        static ItemRun done(Object output) {
            return new ItemRun(output, false);
        }

        static ItemRun failed(String error) {
            return new ItemRun(error, true);
        }
    }
}
