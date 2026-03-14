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

import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * Interpreter-interfaced actor system for managing workflow actors.
 *
 * <p>
 * In order to use {@code ActorRef} from an interpreter,
 * it must have an interface that can call methods by their name strings.
 * The {@code IIActorRef} class exists for this purpose.
 * </p>
 * <p>
 * This {@code IIActorSystem} is a subclass of {@code ActorSystem} with the function of managing {@code IIActorRef}.
 * The {@code IIActorSystem} can manage ordinary {@code ActorRefs} as well as the {@code IIActorRef}s,
 * so the two can be used intermixed in a program.
 * </p>
 *
 * <p>The following methods have been added to manage IIActorRef objects:</p>
 * <ul>
 * <li> {@code addIIActor}</li>
 * <li> {@code getIIActor}</li>
 * <li> {@code hasIIActor}</li>
 * <li> {@code removeIIActor}</li>
 * <li> {@code terminateIIActors}</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 */
public class IIActorSystem extends ActorSystem {

    private static final Logger LOGGER = Logger.getLogger(IIActorSystem.class.getName());
    private static final String CLASS_NAME = IIActorSystem.class.getName();

    ConcurrentHashMap<String, IIActorRef<?>> iiActors = new ConcurrentHashMap<>();

    /** The root actor for the actor hierarchy. */
    private final RootIIAR rootActor;

    /**
     * Constructs a new IIActorSystem with the specified system name.
     *
     * <p>A ROOT actor is automatically created as the top-level parent
     * for all user actors.</p>
     *
     * @param systemName the name of this actor system
     */
    public IIActorSystem(String systemName) {
        super(systemName);
        this.rootActor = new RootIIAR(this);
        iiActors.put(RootIIAR.ROOT_NAME, rootActor);
    }

    /**
     * Constructs a new IIActorSystem with the specified system name and thread count.
     *
     * <p>A ROOT actor is automatically created as the top-level parent
     * for all user actors.</p>
     *
     * @param systemName the name of this actor system
     * @param threadNum the number of threads in the worker pool
     */
    public IIActorSystem(String systemName, int threadNum) {
        super(systemName, threadNum);
        this.rootActor = new RootIIAR(this);
        iiActors.put(RootIIAR.ROOT_NAME, rootActor);
    }

    /**
     * Adds an interpreter-interfaced actor to this system as a child of ROOT.
     *
     * <p>The actor is automatically added as a child of the ROOT actor,
     * establishing the parent-child relationship in the actor tree.</p>
     *
     * <p>If the actor already has a parent set, it is added to the system
     * without modifying the parent-child relationship.</p>
     *
     * @param <T> the type of the actor object
     * @param actor the actor reference to add
     * @return the added actor reference
     */
    public <T> IIActorRef<T> addIIActor(IIActorRef<T> actor) {
        String actorName = actor.getName();
        iiActors.put(actorName, actor);

        // Add as child of ROOT if no parent is set (and it's not ROOT itself)
        if (actor.getParentName() == null && !RootIIAR.ROOT_NAME.equals(actorName)) {
            actor.setParentName(RootIIAR.ROOT_NAME);
            rootActor.getNamesOfChildren().add(actorName);
        }

        return actor;
    }

    /**
     * Retrieves an interpreter-interfaced actor by name.
     *
     * @param <T> the type of the actor object
     * @param name the name of the actor to retrieve
     * @return the actor reference, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    public <T> IIActorRef<T> getIIActor(String name) {
        return (IIActorRef<T>)iiActors.get(name);
    }

    /**
     * Checks if an interpreter-interfaced actor with the given name exists.
     *
     * @param name the name of the actor to check
     * @return {@code true} if the actor exists, {@code false} otherwise
     */
    public boolean hasIIActor(String name) {
        return this.iiActors.containsKey(name);
    }

    /**
     * Removes an interpreter-interfaced actor from this system.
     *
     * <p>If the actor is a direct child of ROOT, it is also removed
     * from ROOT's children list.</p>
     *
     * @param name the name of the actor to remove
     */
    public void removeIIActor(String name) {
        IIActorRef<?> actor = this.iiActors.remove(name);
        if (actor != null && RootIIAR.ROOT_NAME.equals(actor.getParentName())) {
            rootActor.getNamesOfChildren().remove(name);
        }
    }

    /**
     * Returns the number of interpreter-interfaced actors in this system.
     *
     * @return the count of IIActorRef instances
     */
    public int getIIActorCount() {
        return iiActors.size();
    }

    /**
     * Returns the ROOT actor of this system.
     *
     * <p>The ROOT actor is the top-level parent for all user-created actors.
     * Use {@code getRoot().getNamesOfChildren()} to get the names of all
     * top-level actors.</p>
     *
     * @return the ROOT actor
     * @since 2.12.0
     */
    public RootIIAR getRoot() {
        return rootActor;
    }

    /**
     * Returns a list of all top-level actors (direct children of ROOT).
     *
     * <p>This is a convenience method equivalent to:</p>
     * <pre>{@code
     * getRoot().getNamesOfChildren().stream()
     *     .map(this::getIIActor)
     *     .collect(Collectors.toList());
     * }</pre>
     *
     * @return list of top-level actor references
     * @since 2.12.0
     */
    public List<IIActorRef<?>> getTopLevelActors() {
        return rootActor.getNamesOfChildren().stream()
            .map(this::getIIActor)
            .collect(Collectors.toList());
    }

    /**
     * Returns the names of all top-level actors (direct children of ROOT).
     *
     * @return set of top-level actor names
     * @since 2.12.0
     */
    public Set<String> getTopLevelActorNames() {
        return rootActor.getNamesOfChildren();
    }

    /**
     * Returns the names of all interpreter-interfaced actors in this system.
     *
     * <p>This method overrides the base class method to return IIActorRef names
     * instead of regular ActorRef names.</p>
     *
     * @return a list of actor names
     * @since 2.9.0
     */
    @Override
    public List<String> listActorNames() {
        return new ArrayList<>(iiActors.keySet());
    }

    /**
     * Terminates all interpreter-interfaced actors managed by this system.
     *
     * <p>This method closes all registered IIActorRef instances, releasing
     * their associated resources.</p>
     */
    public void terminateIIActors() {
        iiActors.keySet().stream()
            .forEach((name)->iiActors.get(name).close());
    }

    /**
     * Resolves an actor path relative to a given actor using Unix-style path notation.
     *
     * <p>Supports the following path formats:</p>
     * <ul>
     *   <li><code>.</code> or <code>this</code> - self (the actor specified by fromActorName)</li>
     *   <li><code>..</code> - parent actor</li>
     *   <li><code>./*</code> - all children of self</li>
     *   <li><code>../*</code> - all siblings (all children of parent)</li>
     *   <li><code>../sibling</code> - specific sibling by name</li>
     *   <li><code>../web*</code> - siblings whose names start with "web"</li>
     *   <li><code>../*server</code> - siblings whose names end with "server"</li>
     *   <li><code>./child*</code> - children whose names start with "child"</li>
     * </ul>
     *
     * <p>Wildcard patterns:</p>
     * <ul>
     *   <li><code>*</code> - matches all actors in the scope</li>
     *   <li><code>prefix*</code> - matches actors whose names start with "prefix"</li>
     *   <li><code>*suffix</code> - matches actors whose names end with "suffix"</li>
     *   <li><code>*middle*</code> - matches actors whose names contain "middle"</li>
     * </ul>
     *
     * @param fromActorName the name of the actor from which the path is relative
     * @param actorPath Unix-style path (e.g., ".", "this", "..", "../sibling", "../web*")
     * @return list of matching actors (empty list if no matches found)
     *
     * @throws IllegalArgumentException if fromActorName does not exist or path is invalid
     *
     * @since 2.6.0
     */
    public List<IIActorRef<?>> resolveActorPath(String fromActorName, String actorPath) {
        LOGGER.entering(CLASS_NAME, "resolveActorPath", new Object[]{fromActorName, actorPath});

        List<IIActorRef<?>> results = new ArrayList<>();

        // Get the actor from which the path is relative
        IIActorRef<?> fromActor = getIIActor(fromActorName);
        if (fromActor == null) {
            LOGGER.throwing(CLASS_NAME, "resolveActorPath",
                new IllegalArgumentException("Actor not found: " + fromActorName));
            throw new IllegalArgumentException(
                "Actor not found: " + fromActorName);
        }

        // Handle different path patterns
        if (actorPath.equals(".") || actorPath.equals("this")) {
            // Self (Java-style "this" keyword is also supported)
            results.add(fromActor);

        } else if (actorPath.equals("..")) {
            // Parent
            String parentName = fromActor.getParentName();
            if (parentName != null) {
                IIActorRef<?> parent = getIIActor(parentName);
                if (parent != null) {
                    results.add(parent);
                }
            }

        } else if (actorPath.equals("./*")) {
            // All children of self
            Set<String> childNames = fromActor.getNamesOfChildren();
            for (String childName : childNames) {
                IIActorRef<?> child = getIIActor(childName);
                if (child != null) {
                    results.add(child);
                }
            }

        } else if (actorPath.startsWith("./")) {
            // Specific child or wildcard children
            String childPattern = actorPath.substring(2); // Remove "./"
            Set<String> childNames = fromActor.getNamesOfChildren();

            if (childPattern.equals("*")) {
                // Same as "./*"
                for (String childName : childNames) {
                    IIActorRef<?> child = getIIActor(childName);
                    if (child != null) {
                        results.add(child);
                    }
                }
            } else if (childPattern.contains("*")) {
                // Wildcard pattern
                Pattern regex = wildcardToRegex(childPattern);
                for (String childName : childNames) {
                    if (regex.matcher(childName).matches()) {
                        IIActorRef<?> child = getIIActor(childName);
                        if (child != null) {
                            results.add(child);
                        }
                    }
                }
            } else {
                // Exact child name
                IIActorRef<?> child = getIIActor(childPattern);
                if (child != null && childNames.contains(childPattern)) {
                    results.add(child);
                }
            }

        } else if (actorPath.equals("../*")) {
            // All siblings (all children of parent)
            String parentName = fromActor.getParentName();
            if (parentName != null) {
                IIActorRef<?> parent = getIIActor(parentName);
                if (parent != null) {
                    Set<String> siblingNames = parent.getNamesOfChildren();
                    for (String siblingName : siblingNames) {
                        IIActorRef<?> sibling = getIIActor(siblingName);
                        if (sibling != null) {
                            results.add(sibling);
                        }
                    }
                }
            }

        } else if (actorPath.startsWith("../")) {
            // Specific sibling or wildcard siblings
            String siblingPattern = actorPath.substring(3); // Remove "../"
            String parentName = fromActor.getParentName();
            if (parentName != null) {
                IIActorRef<?> parent = getIIActor(parentName);
                if (parent != null) {
                    Set<String> siblingNames = parent.getNamesOfChildren();

                    if (siblingPattern.equals("*")) {
                        // Same as "../*"
                        for (String siblingName : siblingNames) {
                            IIActorRef<?> sibling = getIIActor(siblingName);
                            if (sibling != null) {
                                results.add(sibling);
                            }
                        }
                    } else if (siblingPattern.contains("*")) {
                        // Wildcard pattern
                        Pattern regex = wildcardToRegex(siblingPattern);
                        for (String siblingName : siblingNames) {
                            if (regex.matcher(siblingName).matches()) {
                                IIActorRef<?> sibling = getIIActor(siblingName);
                                if (sibling != null) {
                                    results.add(sibling);
                                }
                            }
                        }
                    } else {
                        // Exact sibling name
                        IIActorRef<?> sibling = getIIActor(siblingPattern);
                        if (sibling != null && siblingNames.contains(siblingPattern)) {
                            results.add(sibling);
                        }
                    }
                }
            }

        } else {
            // Fallback: treat as absolute actor name
            IIActorRef<?> actor = getIIActor(actorPath);
            if (actor != null) {
                results.add(actor);
            }
        }

        LOGGER.logp(Level.FINER, CLASS_NAME, "resolveActorPath",
            "resolved {0} actors: {1}",
            new Object[]{results.size(), results.stream().map(IIActorRef::getName).toList()});
        LOGGER.exiting(CLASS_NAME, "resolveActorPath", results);
        return results;
    }

    /**
     * Converts a wildcard pattern to a regex Pattern.
     *
     * <p>Supported patterns:</p>
     * <ul>
     *   <li><code>*</code> - matches everything</li>
     *   <li><code>prefix*</code> - starts with "prefix"</li>
     *   <li><code>*suffix</code> - ends with "suffix"</li>
     *   <li><code>*middle*</code> - contains "middle"</li>
     * </ul>
     *
     * @param wildcard the wildcard pattern (e.g., "web*", "*server", "*node*")
     * @return compiled regex Pattern
     */
    private Pattern wildcardToRegex(String wildcard) {
        // Escape special regex characters except *
        String escaped = wildcard
            .replace(".", "\\.")
            .replace("?", "\\?")
            .replace("+", "\\+")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("|", "\\|");

        // Convert * to .*
        String regex = escaped.replace("*", ".*");

        return Pattern.compile(regex);
    }

}
