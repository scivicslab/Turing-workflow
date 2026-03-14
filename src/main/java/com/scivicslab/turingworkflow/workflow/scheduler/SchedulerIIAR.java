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

package com.scivicslab.turingworkflow.workflow.scheduler;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for {@link WorkflowScheduler} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link WorkflowScheduler} objects. It delegates action invocations
 * directly to the scheduler's {@link WorkflowScheduler#callByActionName(String, String)} method.</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * IIActorSystem system = new IIActorSystem("my-system");
 *
 * // Create scheduler
 * WorkflowScheduler scheduler = new WorkflowScheduler(system);
 * SchedulerIIAR schedulerRef = new SchedulerIIAR("scheduler", scheduler, system);
 * system.addIIActor(schedulerRef);
 *
 * // Schedule a periodic task
 * schedulerRef.callByActionName("scheduleAtFixedRate",
 *     "healthcheck,server,check,,0,10,SECONDS");
 *
 * // Cancel a task
 * schedulerRef.callByActionName("cancel", "healthcheck");
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.5.0
 * @see WorkflowScheduler
 * @see IIActorRef
 */
public class SchedulerIIAR extends IIActorRef<WorkflowScheduler> {

    /**
     * Constructs a new SchedulerIIAR with the specified actor name and scheduler object.
     *
     * @param actorName the name of this actor
     * @param object the {@link WorkflowScheduler} instance managed by this actor reference
     */
    public SchedulerIIAR(String actorName, WorkflowScheduler object) {
        super(actorName, object);
    }

    /**
     * Constructs a new SchedulerIIAR with the specified actor name, scheduler object,
     * and actor system.
     *
     * @param actorName the name of this actor
     * @param object the {@link WorkflowScheduler} instance managed by this actor reference
     * @param system the actor system managing this actor
     */
    public SchedulerIIAR(String actorName, WorkflowScheduler object, IIActorSystem system) {
        super(actorName, object, system);
    }

    /**
     * Invokes an action on the scheduler by name with the given arguments.
     *
     * <p>This method delegates to the scheduler's
     * {@link WorkflowScheduler#callByActionName(String, String)} method.</p>
     *
     * <p>Supported actions:</p>
     * <ul>
     * <li>{@code scheduleAtFixedRate} - Schedule a task at fixed rate
     *     <br>Args: taskId,targetActor,action,args,initialDelay,period,unit</li>
     * <li>{@code scheduleWithFixedDelay} - Schedule a task with fixed delay
     *     <br>Args: taskId,targetActor,action,args,initialDelay,delay,unit</li>
     * <li>{@code scheduleOnce} - Schedule a one-time task
     *     <br>Args: taskId,targetActor,action,args,delay,unit</li>
     * <li>{@code cancel} - Cancel a scheduled task
     *     <br>Args: taskId</li>
     * <li>{@code getTaskCount} - Get number of scheduled tasks
     *     <br>Args: (none)</li>
     * <li>{@code isScheduled} - Check if a task is scheduled
     *     <br>Args: taskId</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param arg the argument string (format depends on the action)
     * @return an {@link ActionResult} indicating success or failure with a message
     */
    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        return this.object.callByActionName(actionName, arg);
    }
}
