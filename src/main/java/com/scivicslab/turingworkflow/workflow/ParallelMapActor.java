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
import com.scivicslab.pojoactor.core.ManagedThreadPool;

import jakarta.validation.constraints.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in parallel map-over-subworkflow actor. Auto-created on first use as {@code parallel-map}.
 *
 * <p>Where {@code this.call} runs ONE sub-workflow, {@code parallel-map} runs a sub-workflow once per
 * item of a list and answers the outputs.</p>
 *
 * <h2>Action</h2>
 * <ul>
 *   <li>{@code run} — arguments {@code {subWorkflow, items, maxParallel?, baseDir?}}. {@code items} is a JSON
 *       array, usually read from the workflow's own state ({@code items: "jexl: state.select('xs')"}). Each
 *       item's sub-workflow runs in a child actor of this one, an {@link Item}, with an {@link Interpreter}
 *       of its own, so items share no workflow state. The item is put in the sub-workflow's own state at
 *       {@code input}; the sub-workflow leaves its answer in its state at {@code output}. When every item
 *       succeeded, the run is true and its result is the JSON array of the answers in item order, which the
 *       workflow stores with {@code this.putJson {path: ys, value: "jexl: result"}}. Otherwise it is false
 *       with {@code "F of T failed: <index> (<item>): error: ...; ... . parallel-map: mapped T item(s)"},
 *       following {@code Interpreter.apply}, and the workflow chooses what to do with it. A fatal error from
 *       an item's run ({@link ActionResult#fatalIn}) is not one item's failure: it is rethrown, so the
 *       calling workflow records it and ends. There is no time limit.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * <p>No real thread is made here: every item's work runs on the actor system's one
 * {@link ManagedThreadPool} ({@code RealThreadsOutsideManagedThreadPool_261122_oo01}).</p>
 * <ul>
 *   <li>Called on one of that pool's real threads (a workflow step under the default
 *       {@code execution: POOL}, or an item of an outer {@code parallel-map}): each item is given to the
 *       pool from that real thread, so the pool's inline rule runs the items right there, one after
 *       another. Nothing waits for the pool, so nothing can hang it.</li>
 *   <li>Called on any other thread (this actor's mailbox virtual thread, or a workflow thread outside the
 *       pool with {@code execution: DIRECT}): at most {@code maxParallel} items are given to the pool at
 *       once and run on its real threads; the calling thread, which is not a pool real thread, waits.</li>
 * </ul>
 *
 * <h2>What an item can reach</h2>
 * <p>An item's sub-workflow can call the actors of its own subtree and the service actors of the system
 * ({@code out}, {@code parallel-map}, plugin actors the calling workflow made). It cannot reach a loader,
 * so it can neither load a jar nor create an actor, nor a {@code vars} actor or any interpreter outside its
 * own subtree, so it cannot read or write another item's or the calling workflow's state
 * ({@link #reaches}).</p>
 */
public class ParallelMapActor extends IIActorRef<Object> {

    private static final Logger LOG = Logger.getLogger(ParallelMapActor.class.getName());
    private static final int SUB_MAX_ITERATIONS = 100_000;

    /**
     * The largest {@code maxParallel} a run accepts. A run never has more than this many items in flight;
     * items beyond the pool's real threads only wait in its queue, each holding an interpreter, so a value
     * far above any machine's core count buys nothing and is refused before anything is made.
     */
    public static final int MAX_PARALLEL_LIMIT = 256;

    /** Numbers the runs, so that two runs at once name their items apart. */
    private static final AtomicLong RUNS = new AtomicLong();

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
     * @param maxParallel how many items are given to the pool at once, from 1 to {@link #MAX_PARALLEL_LIMIT};
     *                    the pool's real-thread count when absent
     * @param baseDir     the directory the sub-workflow runs in, under the calling interpreter's base
     *                    directory; the caller's base directory when absent
     */
    public record MapArgs(@NotNull String subWorkflow, @NotNull List<Object> items,
                          Integer maxParallel, String baseDir) {}

    @Action(value = "run", argsType = MapArgs.class)
    public ActionResult run(MapArgs args) {
        if (!(parentSystem.getManagedThreadPool() instanceof ManagedThreadPool pool)) {
            return new ActionResult(false, "error: parallel-map.run: the actor system has no ManagedThreadPool");
        }
        final int maxParallel = args.maxParallel() != null ? args.maxParallel() : pool.getCorePoolSize();
        if (maxParallel < 1 || maxParallel > MAX_PARALLEL_LIMIT) {
            return new ActionResult(false, "error: parallel-map.run: maxParallel " + maxParallel
                    + " is outside 1.." + MAX_PARALLEL_LIMIT);
        }
        final String subYaml = args.subWorkflow();
        // The sub-workflows read under the calling interpreter's base directory, as its own path loads
        // do; an explicit baseDir must lie under it. A caller with none restricts nothing.
        final String callerBase = Interpreter.baseDirOfDispatchingInterpreter();
        final String baseDir;
        if (args.baseDir() == null) {
            baseDir = callerBase;
        } else if (callerBase == null) {
            baseDir = args.baseDir();
        } else {
            try {
                baseDir = PathUnderBase.resolve(callerBase, args.baseDir()).toString();
            } catch (java.io.IOException e) {
                return ActionResult.error(e, "parallel-map.run, baseDir");
            }
        }
        final List<Object> items = args.items();
        final String runName = IIActorSystem.scopedChildName(getName(), "run-" + RUNS.incrementAndGet());

        List<ItemRun> outputs = new ArrayList<>(items.size());
        List<String> made = new ArrayList<>(items.size());
        try {
            if (pool.ownsCurrentThread()) {
                // On a pool real thread: ask runs each item inline, here, one after another.
                for (int i = 0; i < items.size(); i++) {
                    CompletableFuture<ItemRun> item = askItem(pool, runName, items.get(i), i, subYaml, baseDir, made, null);
                    pool.awaitHelping(item);
                    outputs.add(item.get());
                }
            } else {
                // Off the pool: at most maxParallel items in flight on the pool's real threads; this
                // thread (not a pool real thread) waits. No time limit on purpose: a sub-workflow run may
                // legitimately take long, and each item's future always completes
                // (TimeoutAndScheduler_251012_oo01: a caller that needs a limit puts it on its own wait).
                Semaphore slots = new Semaphore(maxParallel);
                List<CompletableFuture<ItemRun>> futures = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    slots.acquire();
                    futures.add(askItem(pool, runName, items.get(i), i, subYaml, baseDir, made, slots));
                }
                for (CompletableFuture<ItemRun> f : futures) {
                    outputs.add(f.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error(e, "parallel-map.run, waiting for the items");
        } catch (ExecutionException | java.util.concurrent.CompletionException e) {
            // A fatal error from an item's run is not one item's failure: the JVM is failing, so it goes
            // on out of this action, and the calling workflow records it and ends.
            ActionResult.rethrowIfFatal(e);
            return ActionResult.error(e, "parallel-map.run, a sub-workflow run");
        } finally {
            // Whatever happened, no item of this run outlives it: drop its queued work and its actor.
            synchronized (made) {
                for (String name : made) {
                    pool.cancelJobsForActor(name);
                    removeItem(name);
                }
            }
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
        return new ActionResult(false, failures.size() + " of " + items.size() + " failed: "
                + String.join("; ", failures) + ". " + summary);
    }

    /**
     * Makes item {@code idx} a child actor of this one and asks it, on the pool, to run the sub-workflow.
     * The item's actor is removed as soon as its run ends, and its slot, when there is one, is given back.
     */
    private CompletableFuture<ItemRun> askItem(ManagedThreadPool pool, String runName, Object item, int idx,
                                               String subYaml, String baseDir, List<String> made,
                                               Semaphore slots) {
        String name = runName + ".item-" + idx;
        Interpreter interp = new Interpreter.Builder().loggerName(name).team(parentSystem).build();
        interp.setWorkflowBaseDir(baseDir);
        Item child = new Item(name, interp, parentSystem);
        interp.setSelfActorRef(child);
        child.setParentName(getName());
        getNamesOfChildren().add(name);
        parentSystem.addIIActor(child);
        synchronized (made) {
            made.add(name);
        }
        CompletableFuture<ItemRun> asked;
        try {
            asked = child.ask(i -> runItem(child, i, subYaml, item, idx), pool);
        } catch (Throwable t) {
            // Inline on a pool real thread, a fatal error comes out of ask itself.
            removeItem(name);
            if (slots != null) slots.release();
            throw t;
        }
        return asked.whenComplete((r, t) -> {
            removeItem(name);
            if (slots != null) slots.release();
        });
    }

    /** Takes an item's actor out of the system and out of this actor's children. */
    private void removeItem(String name) {
        getNamesOfChildren().remove(name);
        if (parentSystem.getActor(name) instanceof IIActorRef<?> item) {
            parentSystem.removeIIActor(name);
            item.close();
        }
    }

    /**
     * Runs the sub-workflow for one item, on a pool real thread; returns the {@code output} it left in its
     * state. A fatal error is rethrown rather than made one item's failure.
     */
    private static ItemRun runItem(Item self, Interpreter interp, String subYaml, Object item, int idx) {
        try {
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
            LOG.log(Level.WARNING, "parallel-map item " + idx + " failed", t);
            return ItemRun.failed(ActionResult.errorText(t, "parallel-map item " + idx));
        }
    }

    /**
     * The interpreter actor one item's sub-workflow runs as. Its type marks the boundary of what the
     * sub-workflow can reach ({@link #reaches}).
     */
    static final class Item extends InterpreterIIAR {
        Item(String name, Interpreter interpreter, IIActorSystem system) {
            super(name, interpreter, system);
        }
    }

    /**
     * Whether a workflow running as {@code from} may call {@code target}. Outside any item, every actor.
     * Inside an item (the item itself, or what its {@code this.call} made): every actor of the item's own
     * subtree; outside it, no loader, no {@code vars} and no interpreter, so an item neither creates actors
     * nor touches any workflow state but its own.
     */
    static boolean reaches(IIActorSystem system, IIActorRef<?> from, IIActorRef<?> target) {
        Item item = enclosingItem(system, from);
        if (item == null || target == item || enclosingItem(system, target) == item) {
            return true;
        }
        return !(target instanceof DynamicActorLoaderIIAR)
                && !(target instanceof VarsActor)
                && !(target instanceof InterpreterIIAR);
    }

    /** The nearest {@link Item} among {@code actor} and its ancestors, or null when there is none. */
    private static Item enclosingItem(IIActorSystem system, IIActorRef<?> actor) {
        IIActorRef<?> at = actor;
        for (int depth = 0; at != null && depth < 10_000; depth++) {
            if (at instanceof Item item) {
                return item;
            }
            String parent = at.getParentName();
            at = parent == null ? null
                    : system.getActor(parent) instanceof IIActorRef<?> p ? p : null;
        }
        return null;
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
