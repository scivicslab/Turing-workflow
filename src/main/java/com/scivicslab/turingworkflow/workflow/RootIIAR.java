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

import com.scivicslab.pojoactor.core.ActionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Root actor for the IIActorSystem hierarchy.
 *
 * <p>This actor serves as the top-level parent for all user-created actors
 * in the system. Having a ROOT actor provides several benefits:</p>
 * <ul>
 *   <li>Easy enumeration of top-level actors via {@code getChildren}</li>
 *   <li>Unified actor tree traversal starting from a single root</li>
 *   <li>Clear separation between system infrastructure and user actors</li>
 * </ul>
 *
 * <p>The ROOT actor is automatically created when an {@link IIActorSystem}
 * is instantiated. User actors added via {@link IIActorSystem#addIIActor}
 * become children of ROOT.</p>
 *
 * <h2>Actor Tree Structure</h2>
 * <pre>
 * IIActorSystem
 * └── ROOT
 *     ├── loader
 *     │   └── analyzer
 *     └── nodeGroup
 *         ├── node-192.168.5.13
 *         └── node-192.168.5.14
 * </pre>
 *
 * <h2>Available Actions</h2>
 * <ul>
 *   <li>{@code print} - Prints the argument to standard output</li>
 *   <li>{@code listChildren} - Returns names of all top-level actors</li>
 *   <li>{@code getChildCount} - Returns the number of top-level actors</li>
 *   <li>{@code printTree} - Returns ASCII tree representation of actor hierarchy</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
public class RootIIAR extends IIActorRef<Object> {

    /** The name of the root actor. */
    public static final String ROOT_NAME = "ROOT";

    /** The actor system managing this root actor. */
    private final IIActorSystem system;

    /**
     * Constructs a new RootIIAR.
     *
     * @param system the actor system managing this root actor
     */
    public RootIIAR(IIActorSystem system) {
        super(ROOT_NAME, new Object(), system);
        this.system = system;
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return switch (actionName) {
            case "print" -> print(args);
            case "listChildren" -> listChildren();
            case "getChildCount" -> getChildCount();
            case "printTree" -> printTree();
            default -> super.callByActionName(actionName, args);
        };
    }

    /**
     * Prints the argument to standard output.
     *
     * @param args the message to print (may be JSON array format)
     * @return ActionResult indicating success
     */
    private ActionResult print(String args) {
        String message = parseFirstArgument(args);
        System.out.println(message);
        return new ActionResult(true, "Printed: " + message);
    }

    /**
     * Lists all top-level actor names.
     *
     * @return ActionResult containing comma-separated list of child actor names
     */
    private ActionResult listChildren() {
        Set<String> children = getNamesOfChildren();
        if (children.isEmpty()) {
            return new ActionResult(true, "No children");
        }
        String result = children.stream()
            .sorted()
            .collect(Collectors.joining(", "));
        return new ActionResult(true, result);
    }

    /**
     * Returns the number of top-level actors.
     *
     * @return ActionResult containing the child count
     */
    private ActionResult getChildCount() {
        return new ActionResult(true, String.valueOf(getNamesOfChildren().size()));
    }

    /**
     * Returns an ASCII tree representation of the actor hierarchy.
     *
     * <p>Example output:</p>
     * <pre>
     * ROOT
     * ├── loader
     * │   └── aggregator
     * ├── nodeGroup
     * │   ├── node-192.168.5.13
     * │   └── node-192.168.5.14
     * └── outputMultiplexer
     * </pre>
     *
     * @return ActionResult containing the tree representation
     */
    private ActionResult printTree() {
        StringBuilder sb = new StringBuilder();
        sb.append(ROOT_NAME).append("\n");

        List<String> children = getNamesOfChildren().stream()
            .sorted()
            .collect(Collectors.toList());

        for (int i = 0; i < children.size(); i++) {
            boolean isLast = (i == children.size() - 1);
            String childName = children.get(i);
            appendActorTree(sb, childName, "", isLast);
        }

        return new ActionResult(true, sb.toString());
    }

    /**
     * Recursively appends an actor and its children to the tree representation.
     *
     * @param sb the StringBuilder to append to
     * @param actorName the name of the current actor
     * @param prefix the prefix for indentation
     * @param isLast whether this is the last child in its parent
     */
    private void appendActorTree(StringBuilder sb, String actorName, String prefix, boolean isLast) {
        String connector = isLast ? "└── " : "├── ";
        sb.append(prefix).append(connector).append(actorName).append("\n");

        IIActorRef<?> actor = system.getIIActor(actorName);
        if (actor == null) {
            return;
        }

        Set<String> childNames = actor.getNamesOfChildren();
        if (childNames.isEmpty()) {
            return;
        }

        String newPrefix = prefix + (isLast ? "    " : "│   ");
        List<String> sortedChildren = new ArrayList<>(childNames);
        sortedChildren.sort(String::compareTo);

        for (int i = 0; i < sortedChildren.size(); i++) {
            boolean childIsLast = (i == sortedChildren.size() - 1);
            appendActorTree(sb, sortedChildren.get(i), newPrefix, childIsLast);
        }
    }
}
