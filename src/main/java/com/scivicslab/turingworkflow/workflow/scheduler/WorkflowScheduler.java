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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * A scheduler for periodic task execution with IIActorRef (workflow actors).
 *
 * <p>This class provides scheduling capabilities for workflow actors using
 * action names and string arguments. Tasks are executed on actors via
 * {@link IIActorRef#callByActionName(String, String)}.</p>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * IIActorSystem system = new IIActorSystem("my-system");
 * WorkflowScheduler scheduler = new WorkflowScheduler(system);
 *
 * // Schedule a task to run every 10 seconds
 * scheduler.scheduleAtFixedRate("health-check", "serverActor", "checkHealth", "", 0, 10, TimeUnit.SECONDS);
 *
 * // Cancel a task
 * scheduler.cancelTask("health-check");
 *
 * // Cleanup
 * scheduler.close();
 * }</pre>
 *
 * <p>For lambda-based scheduling with ActorRef, use
 * {@link com.scivicslab.pojoactor.core.scheduler.Scheduler} instead.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.11.0
 * @see com.scivicslab.pojoactor.core.scheduler.Scheduler
 * @see IIActorRef
 */
public class WorkflowScheduler implements CallableByActionName, AutoCloseable {

    private static final Logger logger = Logger.getLogger(WorkflowScheduler.class.getName());

    private final IIActorSystem actorSystem;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks;

    /**
     * Constructs a new WorkflowScheduler with the specified actor system.
     *
     * @param actorSystem the actor system containing target actors
     */
    public WorkflowScheduler(IIActorSystem actorSystem) {
        this(actorSystem, 2);
    }

    /**
     * Constructs a new WorkflowScheduler with the specified actor system and pool size.
     *
     * @param actorSystem the actor system containing target actors
     * @param poolSize the number of threads in the scheduler's thread pool
     */
    public WorkflowScheduler(IIActorSystem actorSystem, int poolSize) {
        this.actorSystem = actorSystem;
        this.executor = Executors.newScheduledThreadPool(poolSize, (Runnable r) -> {
            Thread t = new Thread(r, "WorkflowScheduler-Worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduledTasks = new ConcurrentHashMap<>();
    }

    /**
     * Schedules a task to execute periodically at a fixed rate.
     *
     * <p><strong>Fixed Rate vs Fixed Delay:</strong></p>
     * <p>With {@code scheduleAtFixedRate}, executions are scheduled to start at
     * regular intervals (0, period, 2*period, 3*period, ...) regardless of how
     * long each execution takes. If an execution takes longer than the period,
     * the next execution starts immediately after the previous one completes
     * (no delay accumulation).</p>
     *
     * <p>Use this method when you need consistent timing intervals, such as
     * metrics collection or heartbeat checks at precise intervals.</p>
     *
     * <p>In contrast, {@link #scheduleWithFixedDelay} waits for a fixed delay
     * <em>after</em> each execution completes before starting the next one.</p>
     *
     * <pre>
     * scheduleAtFixedRate (period=100ms, task takes 30ms):
     * |task|          |task|          |task|
     * 0    30   100   130   200   230   300ms
     *      └─period─┘      └─period─┘
     * </pre>
     *
     * @param taskId unique identifier for this scheduled task
     * @param targetActorName name of the target actor in the system
     * @param actionName action to invoke on the actor
     * @param actionArgs arguments to pass to the action
     * @param initialDelay delay before first execution
     * @param period interval between successive execution starts
     * @param unit time unit for the delays
     * @return the taskId for reference
     * @see #scheduleWithFixedDelay
     */
    public String scheduleAtFixedRate(String taskId, String targetActorName,
                                       String actionName, String actionArgs,
                                       long initialDelay, long period, TimeUnit unit) {
        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            executeOnActor(taskId, targetActorName, actionName, actionArgs);
        }, initialDelay, period, unit);

        registerTask(taskId, task);

        logger.log(Level.INFO, String.format(
            "Scheduled at fixed rate: %s -> %s.%s (initial=%d, period=%d %s)",
            taskId, targetActorName, actionName, initialDelay, period, unit));

        return taskId;
    }

    /**
     * Schedules a task to execute periodically with a fixed delay between executions.
     *
     * <p><strong>Fixed Delay vs Fixed Rate:</strong></p>
     * <p>With {@code scheduleWithFixedDelay}, the delay between the <em>termination</em>
     * of one execution and the <em>start</em> of the next is always {@code delay} time
     * units. This ensures that executions never overlap and there is always a guaranteed
     * rest period between executions.</p>
     *
     * <p>Use this method when you need to ensure a minimum gap between executions,
     * such as polling operations where you want to avoid overwhelming a resource,
     * or when each execution depends on external state that needs time to stabilize.</p>
     *
     * <p>In contrast, {@link #scheduleAtFixedRate} schedules executions at fixed
     * intervals regardless of execution duration.</p>
     *
     * <pre>
     * scheduleWithFixedDelay (delay=100ms, task takes 30ms):
     * |task|              |task|              |task|
     * 0    30        130  160        260  290ms
     *      └──delay──┘    └──delay──┘
     * </pre>
     *
     * @param taskId unique identifier for this scheduled task
     * @param targetActorName name of the target actor in the system
     * @param actionName action to invoke on the actor
     * @param actionArgs arguments to pass to the action
     * @param initialDelay delay before first execution
     * @param delay delay between termination of one execution and start of next
     * @param unit time unit for the delays
     * @return the taskId for reference
     * @see #scheduleAtFixedRate
     */
    public String scheduleWithFixedDelay(String taskId, String targetActorName,
                                          String actionName, String actionArgs,
                                          long initialDelay, long delay, TimeUnit unit) {
        ScheduledFuture<?> task = executor.scheduleWithFixedDelay(() -> {
            executeOnActor(taskId, targetActorName, actionName, actionArgs);
        }, initialDelay, delay, unit);

        registerTask(taskId, task);

        logger.log(Level.INFO, String.format(
            "Scheduled with fixed delay: %s -> %s.%s (initial=%d, delay=%d %s)",
            taskId, targetActorName, actionName, initialDelay, delay, unit));

        return taskId;
    }

    /**
     * Schedules a task to execute once after a specified delay.
     *
     * @param taskId unique identifier for this scheduled task
     * @param targetActorName name of the target actor in the system
     * @param actionName action to invoke on the actor
     * @param actionArgs arguments to pass to the action
     * @param delay delay before execution
     * @param unit time unit for the delay
     * @return the taskId for reference
     */
    public String scheduleOnce(String taskId, String targetActorName,
                                String actionName, String actionArgs,
                                long delay, TimeUnit unit) {
        ScheduledFuture<?> task = executor.schedule(() -> {
            try {
                executeOnActor(taskId, targetActorName, actionName, actionArgs);
            } finally {
                scheduledTasks.remove(taskId);
            }
        }, delay, unit);

        registerTask(taskId, task);

        logger.log(Level.INFO, String.format(
            "Scheduled once: %s -> %s.%s (delay=%d %s)",
            taskId, targetActorName, actionName, delay, unit));

        return taskId;
    }

    /**
     * Cancels a scheduled task.
     *
     * @param taskId identifier of the task to cancel
     * @return true if the task was found and cancelled, false otherwise
     */
    public boolean cancelTask(String taskId) {
        ScheduledFuture<?> task = scheduledTasks.remove(taskId);
        if (task != null) {
            boolean cancelled = task.cancel(false);
            logger.log(Level.INFO, cancelled ?
                "Cancelled: " + taskId :
                "Task already completed or cancelled: " + taskId);
            return cancelled;
        }
        logger.log(Level.WARNING, "Task not found: " + taskId);
        return false;
    }

    /**
     * Returns the number of currently scheduled tasks.
     *
     * @return the number of active scheduled tasks
     */
    public int getScheduledTaskCount() {
        return scheduledTasks.size();
    }

    /**
     * Checks if a task with the given ID is currently scheduled.
     *
     * @param taskId the task identifier to check
     * @return true if the task exists and is not cancelled, false otherwise
     */
    public boolean isScheduled(String taskId) {
        ScheduledFuture<?> task = scheduledTasks.get(taskId);
        return task != null && !task.isCancelled() && !task.isDone();
    }

    /**
     * Shuts down the scheduler and cancels all scheduled tasks.
     */
    @Override
    public void close() {
        logger.log(Level.INFO, "Shutting down workflow scheduler, cancelling " +
            scheduledTasks.size() + " scheduled tasks");

        scheduledTasks.values().forEach((ScheduledFuture<?> task) -> task.cancel(false));
        scheduledTasks.clear();

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.log(Level.WARNING,
                    "Scheduler did not terminate within 5 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.log(Level.WARNING,
                "Interrupted while waiting for scheduler termination", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== CallableByActionName Implementation ==========

    /**
     * Executes an action by name with the given arguments.
     *
     * <p>Supported actions:</p>
     * <ul>
     *   <li>{@code scheduleAtFixedRate} - Args: taskId,targetActor,action,args,initialDelay,period,unit</li>
     *   <li>{@code scheduleWithFixedDelay} - Args: taskId,targetActor,action,args,initialDelay,delay,unit</li>
     *   <li>{@code scheduleOnce} - Args: taskId,targetActor,action,args,delay,unit</li>
     *   <li>{@code cancel} - Args: taskId</li>
     *   <li>{@code getTaskCount} - Args: (none)</li>
     *   <li>{@code isScheduled} - Args: taskId</li>
     * </ul>
     *
     * @param actionName the action to execute
     * @param arg comma-separated arguments
     * @return ActionResult indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        try {
            switch (actionName) {
                case "scheduleAtFixedRate":
                    return handleScheduleAtFixedRate(arg);
                case "scheduleWithFixedDelay":
                    return handleScheduleWithFixedDelay(arg);
                case "scheduleOnce":
                    return handleScheduleOnce(arg);
                case "cancel":
                    return handleCancel(arg);
                case "getTaskCount":
                    return new ActionResult(true, String.valueOf(getScheduledTaskCount()));
                case "isScheduled":
                    return new ActionResult(true, String.valueOf(isScheduled(arg.trim())));
                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in action " + actionName, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    // ========== Private Helper Methods ==========

    private void executeOnActor(String taskId, String targetActorName,
                                 String actionName, String actionArgs) {
        try {
            IIActorRef<?> actor = actorSystem.getIIActor(targetActorName);
            if (actor == null) {
                logger.log(Level.WARNING, String.format(
                    "Actor not found for scheduled task %s: %s",
                    taskId, targetActorName));
                return;
            }

            ActionResult result = actor.callByActionName(actionName, actionArgs);
            if (!result.isSuccess()) {
                logger.log(Level.WARNING, String.format(
                    "Scheduled task %s failed: %s",
                    taskId, result.getResult()));
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format(
                "Error executing scheduled task %s: %s",
                taskId, e.getMessage()), e);
        }
    }

    private void registerTask(String taskId, ScheduledFuture<?> task) {
        ScheduledFuture<?> oldTask = scheduledTasks.put(taskId, task);
        if (oldTask != null) {
            oldTask.cancel(false);
            logger.log(Level.INFO, "Replaced existing scheduled task: " + taskId);
        }
    }

    private ActionResult handleScheduleAtFixedRate(String arg) {
        String[] parts = parseArgs(arg, 7);
        if (parts == null) {
            return new ActionResult(false,
                "scheduleAtFixedRate requires: taskId,targetActor,action,args,initialDelay,period,unit");
        }

        String taskId = scheduleAtFixedRate(
            parts[0],                         // taskId
            parts[1],                         // targetActorName
            parts[2],                         // actionName
            parts[3],                         // actionArgs
            Long.parseLong(parts[4]),         // initialDelay
            Long.parseLong(parts[5]),         // period
            TimeUnit.valueOf(parts[6].toUpperCase())  // unit
        );

        return new ActionResult(true, "Scheduled: " + taskId);
    }

    private ActionResult handleScheduleWithFixedDelay(String arg) {
        String[] parts = parseArgs(arg, 7);
        if (parts == null) {
            return new ActionResult(false,
                "scheduleWithFixedDelay requires: taskId,targetActor,action,args,initialDelay,delay,unit");
        }

        String taskId = scheduleWithFixedDelay(
            parts[0],                         // taskId
            parts[1],                         // targetActorName
            parts[2],                         // actionName
            parts[3],                         // actionArgs
            Long.parseLong(parts[4]),         // initialDelay
            Long.parseLong(parts[5]),         // delay
            TimeUnit.valueOf(parts[6].toUpperCase())  // unit
        );

        return new ActionResult(true, "Scheduled: " + taskId);
    }

    private ActionResult handleScheduleOnce(String arg) {
        String[] parts = parseArgs(arg, 6);
        if (parts == null) {
            return new ActionResult(false,
                "scheduleOnce requires: taskId,targetActor,action,args,delay,unit");
        }

        String taskId = scheduleOnce(
            parts[0],                         // taskId
            parts[1],                         // targetActorName
            parts[2],                         // actionName
            parts[3],                         // actionArgs
            Long.parseLong(parts[4]),         // delay
            TimeUnit.valueOf(parts[5].toUpperCase())  // unit
        );

        return new ActionResult(true, "Scheduled: " + taskId);
    }

    private ActionResult handleCancel(String arg) {
        String taskId = arg.trim();
        boolean cancelled = cancelTask(taskId);
        return cancelled ?
            new ActionResult(true, "Cancelled: " + taskId) :
            new ActionResult(false, "Task not found or already cancelled: " + taskId);
    }

    private String[] parseArgs(String arg, int expectedParts) {
        if (arg == null || arg.isEmpty()) {
            return null;
        }
        String[] parts = arg.split(",", expectedParts);
        if (parts.length < expectedParts) {
            return null;
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
