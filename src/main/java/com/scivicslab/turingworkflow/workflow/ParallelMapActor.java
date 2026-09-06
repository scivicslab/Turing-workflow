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
import org.json.JSONArray;

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
 * runs a sub-workflow once per item of a {@code list:} actor, concurrently, and collects the outputs.</p>
 *
 * <h2>Action</h2>
 * <ul>
 *   <li>{@code run} — arguments {@code ["<sub-workflow.yaml>", "<inputList>", "<outputList>", maxParallel?, baseDir?]}.
 *       For each item in {@code inputList} (a {@code list:} actor), runs the sub-workflow in its OWN isolated
 *       child {@link IIActorSystem} so per-task state — and the per-task {@code llm} actor — never collide and
 *       execution is genuinely parallel. The item is seeded into {@code str:input}; the sub-workflow's result is
 *       read from {@code str:output}. Outputs are appended to {@code outputList} in input order.</li>
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

    @Action("run")
    public ActionResult run(String args) {
        final String subYaml;
        final String inListName;
        final String outListName;
        final int maxParallel;
        final String baseDir;
        try {
            JSONArray a = new JSONArray(args);
            subYaml = a.getString(0);
            inListName = a.getString(1);
            outListName = a.getString(2);
            maxParallel = a.length() >= 4 ? a.getInt(3) : DEFAULT_PARALLEL;
            baseDir = a.length() >= 5 ? a.getString(4) : ".";
        } catch (Exception e) {
            return new ActionResult(false, "parallel-map.run: expected "
                    + "[\"sub.yaml\",\"inputList\",\"outputList\",maxParallel?,baseDir?]: " + e.getMessage());
        }

        // Read the input items from the parent's list actor.
        IIActorRef<?> inList = parentSystem.getIIActor(inListName);
        if (inList == null) {
            return new ActionResult(false, "parallel-map.run: input list not found: " + inListName);
        }
        int n;
        try {
            n = Integer.parseInt(inList.callByActionName("size", "").getResult().trim());
        } catch (Exception e) {
            return new ActionResult(false, "parallel-map.run: cannot read size of " + inListName);
        }
        List<String> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ActionResult r = inList.callByActionName("get", String.valueOf(i));
            items.add(r.isSuccess() ? r.getResult() : "");
        }

        // Map: run the sub-workflow per item in parallel, each in its own isolated child system.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, maxParallel));
        List<Future<String>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final String item = items.get(i);
            futures.add(pool.submit(() -> runOne(subYaml, baseDir, item, idx)));
        }
        List<String> outputs = new ArrayList<>(items.size());
        try {
            for (Future<String> f : futures) {
                outputs.add(f.get());
            }
        } catch (Exception e) {
            pool.shutdownNow();
            return new ActionResult(false, "parallel-map.run: a task failed: " + e.getMessage());
        } finally {
            pool.shutdown();
        }

        // Collect: append outputs to the parent's output list, in input order.
        IIActorRef<?> outList = parentSystem.getIIActor(outListName);
        if (outList == null) {
            return new ActionResult(false, "parallel-map.run: output list not found: " + outListName);
        }
        for (String o : outputs) {
            outList.callByActionName("add", o == null ? "" : o);
        }
        return new ActionResult(true, "parallel-map: mapped " + items.size() + " item(s)");
    }

    /** Runs the sub-workflow for one item in an isolated child system; returns its {@code str:output}. */
    private String runOne(String subYaml, String baseDir, String item, int idx) {
        IIActorSystem sub = new IIActorSystem("pm-" + getName() + "-" + idx, 2);
        try {
            Interpreter interp = new Interpreter.Builder().loggerName("pm-" + idx).team(sub).build();
            interp.setWorkflowBaseDir(baseDir);
            InterpreterIIAR self = new InterpreterIIAR("interpreter", interp, sub);
            interp.setSelfActorRef(self);
            sub.addIIActor(self);
            // Equip the isolated system with the standard helpers the sub-workflow may use.
            sub.addIIActor(new DynamicActorLoaderIIAR("loader", sub));
            sub.addIIActor(new VarsActor(sub, new HashMap<>()));

            // Seed the per-task input, run the sub-workflow, read the per-task output.
            sub.getIIActor("str:input").callByActionName("set", item);
            ActionResult r = interp.runWorkflow(subYaml, SUB_MAX_ITERATIONS);
            if (!r.isSuccess()) {
                return "error: sub-workflow failed: " + r.getResult();
            }
            IIActorRef<?> out = sub.getIIActor("str:output");
            return out == null ? "" : out.callByActionName("get", "").getResult();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "parallel-map task " + idx + " failed", e);
            return "error: " + e.getMessage();
        } finally {
            // Shut down BOTH the actors (close hooks) AND the isolated system's thread pool. Without
            // terminate(), each task leaks a non-daemon executor: harmless when the host exits via
            // System.exit (CLI), but a thread leak in a long-running host (e.g. the chat-ui3 server)
            // and enough to keep a plain JVM from exiting.
            try { sub.terminateIIActors(); } catch (Exception ignore) { /* best effort */ }
            try { sub.terminate(); } catch (Exception ignore) { /* best effort */ }
        }
    }
}
