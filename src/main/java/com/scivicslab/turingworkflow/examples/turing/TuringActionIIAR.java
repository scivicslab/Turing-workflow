/*
 * Copyright 2025 devteam@scivics-lab.com
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

package com.scivicslab.turingworkflow.examples.turing;

import static com.scivicslab.pojoactor.action.ActionArgs.*;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for Turing machine using @Action annotation.
 *
 * <p>This is the Part 2-3 version that uses {@code @Action} annotations instead of
 * overriding {@code callByActionName()}. Compare with {@link TuringIIAR} for the
 * Part 2-2 switch-statement approach.</p>
 *
 * @author devteam@scivics-lab.com
 * @see TuringIIAR
 */
public class TuringActionIIAR extends IIActorRef<Turing> {

    /**
     * Constructs a new TuringActionIIAR for dynamic loading.
     * Creates a new Turing machine instance automatically.
     *
     * @param actorName the name of the actor
     * @param system the actor system managing this actor
     */
    public TuringActionIIAR(String actorName, IIActorSystem system) {
        super(actorName, new Turing(), system);
    }

    /**
     * Constructs a new TuringActionIIAR with the specified actor name and Turing instance.
     *
     * @param actorName the name of the actor
     * @param turing the Turing machine instance
     * @param system the actor system managing this actor
     */
    public TuringActionIIAR(String actorName, Turing turing, IIActorSystem system) {
        super(actorName, turing, system);
    }

    /**
     * The symbol to write at the head's current position.
     *
     * @param value the symbol to write
     */
    public record PutArgs(@NotNull String value) {}

    /**
     * Which way to move the head.
     *
     * @param direction {@code "L"} or {@code "R"}
     */
    public record MoveArgs(@NotNull String direction) {}

    /**
     * The symbol the head's current position is compared against.
     *
     * @param expected the symbol to compare with
     */
    public record MatchCurrentValueArgs(@NotNull String expected) {}

    @Action("initMachine")
    public ActionResult initMachine(String args) {
        this.object.initMachine();
        return new ActionResult(true, "Machine initialized");
    }

    @Action(value = "put", argsType = PutArgs.class)
    public ActionResult put(PutArgs args) {
        this.object.put(args.value());
        return new ActionResult(true, "Put " + args.value());
    }

    @Action(value = "move", argsType = MoveArgs.class)
    public ActionResult move(MoveArgs args) {
        this.object.move(args.direction());
        return new ActionResult(true, "Moved " + args.direction());
    }

    @Action("printTape")
    public ActionResult printTape(String args) {
        this.object.printTape();
        return new ActionResult(true, "Tape printed");
    }

    @Action("increment")
    public ActionResult increment(String args) {
        int count = this.object.increment();
        return new ActionResult(true, "Counter: " + count);
    }

    // Conditional branching actions (used in turing87)

    @Action(value = "matchCurrentValue", argsType = MatchCurrentValueArgs.class)
    public ActionResult matchCurrentValue(MatchCurrentValueArgs args) {
        boolean match = this.object.matchCurrentValue(args.expected());
        return new ActionResult(match, "match=" + match);
    }

    @Action("isAny")
    public ActionResult isAny(String args) {
        boolean any = this.object.isAny();
        return new ActionResult(any, "isAny=" + any);
    }

    @Action("isNone")
    public ActionResult isNone(String args) {
        boolean none = this.object.isNone();
        return new ActionResult(none, "isNone=" + none);
    }

    // No need to override callByActionName()!
}
