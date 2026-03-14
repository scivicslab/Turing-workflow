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
import com.scivicslab.pojoactor.core.ActorProvider;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.core.DynamicActorLoader;

import org.json.JSONArray;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic actor that dynamically loads and creates other actors from plugins.
 *
 * <p>This actor enables workflows to load actors from external JAR files or
 * ServiceLoader providers at runtime, without restarting the application.</p>
 *
 * <p><strong>Supported Actions:</strong></p>
 * <ul>
 *   <li>loadJar: Load a JAR file and make its classes available</li>
 *   <li>createChild: Create an actor from a loaded class under a parent actor</li>
 *   <li>listLoadedJars: List all loaded JAR files</li>
 *   <li>loadFromJar: (Legacy) Load actor from external JAR file in one step</li>
 *   <li>createFromProvider: Create actor from ServiceLoader provider</li>
 *   <li>listProviders: List all available ActorProvider instances</li>
 *   <li>loadProvidersFromJar: Load ActorProvider plugins from JAR</li>
 * </ul>
 *
 * <p><strong>Example Workflow (Two-step loading - Recommended):</strong></p>
 * <pre>{@code
 * steps:
 *   # Step 1: Load JAR (makes classes available)
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: loader
 *         method: loadJar
 *         arguments: ["plugins/my-actor.jar"]
 *
 *   # Step 2: Create actor under a parent
 *   - states: ["1", "2"]
 *     actions:
 *       - actor: loader
 *         method: createChild
 *         arguments: ["nodeGroup", "myactor", "com.example.MyActor"]
 *
 *   # Step 3: Use the actor
 *   - states: ["2", "end"]
 *     actions:
 *       - actor: myactor
 *         method: someAction
 *         arguments: ["arg1", "arg2"]
 * }</pre>
 *
 * <p><strong>Example Workflow (Legacy one-step loading):</strong></p>
 * <pre>{@code
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: loader
 *         method: loadFromJar
 *         arguments: ["plugins/my-actor.jar", "com.example.MyActor", "myactor"]
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.6.0
 */
public class DynamicActorLoaderActor implements CallableByActionName {

    protected final IIActorSystem system;

    /** Map of JAR path to ClassLoader for loaded JARs */
    private final Map<String, URLClassLoader> loadedJars = new LinkedHashMap<>();

    /**
     * Constructs a new DynamicActorLoaderActor.
     *
     * @param system the actor system to register newly loaded actors
     */
    public DynamicActorLoaderActor(IIActorSystem system) {
        this.system = system;
    }

    private static final Logger logger = Logger.getLogger(DynamicActorLoaderActor.class.getName());

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        logger.info("DynamicActorLoader: action='" + actionName + "' args='" + args + "'");
        try {
            switch (actionName) {
                case "loadJar":
                    return loadJar(args);

                case "createChild":
                    return createChild(args);

                case "listLoadedJars":
                    return listLoadedJars();

                case "loadFromJar":
                    return loadFromJar(args);

                case "createFromProvider":
                    return createFromProvider(args);

                case "listProviders":
                    return listProviders();

                case "loadProvidersFromJar":
                    return loadProvidersFromJar(args);

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (Exception e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Loads a JAR file and makes its classes available for actor creation.
     *
     * <p>Arguments format:</p>
     * <pre>{@code
     * # File path (absolute or relative)
     * arguments: ["plugins/my-plugin.jar"]
     *
     * # Maven coordinate (resolved from ~/.m2/repository/)
     * arguments: ["com.scivicslab:turing-workflow:1.0.0"]
     * }</pre>
     *
     * <p>After loading, use {@code createChild} to create actors from classes in the JAR.</p>
     *
     * @param args JSON array or plain string containing the JAR path or Maven coordinate
     * @return ActionResult indicating success or failure
     */
    private ActionResult loadJar(String args) {
        try {
            String jarSpec;

            // Try to parse as JSON array first
            if (args.trim().startsWith("[")) {
                JSONArray jsonArray = new JSONArray(args);
                if (jsonArray.length() < 1) {
                    return new ActionResult(false,
                        "Invalid args. Expected array: [jarPath or groupId:artifactId:version]");
                }
                jarSpec = jsonArray.getString(0).trim();
            } else {
                jarSpec = args.trim();
            }

            // Resolve Maven coordinate if applicable
            String jarPath = resolveMavenCoordinate(jarSpec);

            // Check if already loaded
            if (loadedJars.containsKey(jarPath)) {
                return new ActionResult(true, "JAR already loaded: " + jarPath);
            }

            // Load the JAR
            Path path = Paths.get(jarPath);
            if (!path.toFile().exists()) {
                return new ActionResult(false, "JAR not found: " + jarPath);
            }
            URL jarUrl = path.toUri().toURL();

            URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarUrl},
                getClass().getClassLoader()
            );

            loadedJars.put(jarPath, classLoader);

            return new ActionResult(true, "Loaded JAR: " + jarPath);

        } catch (Exception e) {
            return new ActionResult(false, "Failed to load JAR: " + e.getMessage());
        }
    }

    /**
     * Resolves a Maven coordinate (groupId:artifactId:version) to a JAR file path
     * in the local Maven repository (~/.m2/repository/).
     *
     * <p>If the input does not look like a Maven coordinate (no colons or wrong number
     * of segments), it is returned as-is (treated as a file path).</p>
     *
     * @param spec either a file path or a Maven coordinate like "com.scivicslab:turing-workflow:1.0.0"
     * @return the resolved file path
     */
    private String resolveMavenCoordinate(String spec) {
        String[] parts = spec.split(":");
        if (parts.length != 3) {
            return spec; // Not a Maven coordinate, return as-is
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        // ~/.m2/repository/com/scivicslab/turing-workflow/1.0.0/turing-workflow-1.0.0.jar
        String m2Repo = System.getProperty("user.home") + "/.m2/repository";
        String groupPath = groupId.replace('.', '/');

        return String.join("/", m2Repo, groupPath, artifactId, version,
            artifactId + "-" + version + ".jar");
    }

    /**
     * Creates a child actor from a loaded class under a specified parent actor.
     *
     * <p>Arguments format:</p>
     * <pre>{@code
     * arguments: ["parentActorName", "newActorName", "com.example.MyActorClass"]
     * }</pre>
     *
     * <p>The class can be either:</p>
     * <ul>
     *   <li>An {@link IIActorRef} subclass with constructor (String actorName, IIActorSystem system)</li>
     *   <li>A POJO implementing {@link CallableByActionName} (legacy, wrapped with GenericIIAR)</li>
     * </ul>
     *
     * @param args JSON array: [parentActorName, actorName, className]
     * @return ActionResult indicating success or failure
     */
    private ActionResult createChild(String args) {
        try {
            String parentName;
            String actorName;
            String className;

            // Parse as JSON array
            if (args.trim().startsWith("[")) {
                JSONArray jsonArray = new JSONArray(args);
                if (jsonArray.length() < 3) {
                    return new ActionResult(false,
                        "Invalid args. Expected array: [parentActorName, actorName, className]");
                }
                parentName = jsonArray.getString(0).trim();
                actorName = jsonArray.getString(1).trim();
                className = jsonArray.getString(2).trim();
            } else {
                // Fall back to comma-separated format
                String[] parts = args.split(",", 3);
                if (parts.length < 3) {
                    return new ActionResult(false,
                        "Invalid args. Expected: parentActorName,actorName,className");
                }
                parentName = parts[0].trim();
                actorName = parts[1].trim();
                className = parts[2].trim();
            }

            // Find parent actor
            IIActorRef<?> parent = system.getIIActor(parentName);
            if (parent == null) {
                return new ActionResult(false, "Parent actor not found: " + parentName);
            }

            // Try to load class: first from system classpath, then from loaded JARs
            Class<?> clazz = null;

            // 1. Try system classpath first
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                // Not on system classpath, try loaded JARs
            }

            // 2. If not found, try loaded JARs
            if (clazz == null) {
                for (URLClassLoader loader : loadedJars.values()) {
                    try {
                        clazz = loader.loadClass(className);
                        break;
                    } catch (ClassNotFoundException e) {
                        // Try next loader
                    }
                }
            }

            if (clazz == null) {
                return new ActionResult(false,
                    "Class not found: " + className +
                    ". Check the class name or load the JAR first.");
            }

            IIActorRef<?> actorRef;

            // Check if class is an IIActorRef subclass (new pattern: POJO + IIAR)
            if (IIActorRef.class.isAssignableFrom(clazz)) {
                // Instantiate IIAR with (actorName, system) constructor
                try {
                    actorRef = (IIActorRef<?>) clazz
                        .getDeclaredConstructor(String.class, IIActorSystem.class)
                        .newInstance(actorName, system);
                } catch (NoSuchMethodException e) {
                    return new ActionResult(false,
                        "IIActorRef subclass must have constructor (String actorName, IIActorSystem system): " + className);
                }
            } else {
                // Legacy pattern: POJO implementing CallableByActionName
                Object instance = clazz.getDeclaredConstructor().newInstance();

                // Verify it implements CallableByActionName
                if (!(instance instanceof CallableByActionName)) {
                    return new ActionResult(false,
                        "Class must be IIActorRef subclass or implement CallableByActionName: " + className);
                }

                // Inject ActorSystem if the plugin implements ActorSystemAware
                if (instance instanceof ActorSystemAware) {
                    ((ActorSystemAware) instance).setActorSystem(system);
                }

                // Wrap as IIActorRef
                actorRef = new GenericIIAR<>(actorName, instance, system);

                // Inject IIActorRef if the plugin implements IIActorRefAware
                if (instance instanceof IIActorRefAware) {
                    ((IIActorRefAware) instance).setIIActorRef(actorRef);
                }
            }

            // Set parent-child relationship
            actorRef.setParentName(parentName);
            parent.getNamesOfChildren().add(actorName);

            // Register with system
            system.addIIActor(actorRef);

            return new ActionResult(true,
                "Created actor '" + actorName + "' under '" + parentName +
                "' from class: " + className);

        } catch (Exception e) {
            return new ActionResult(false, "Failed to create child: " + e.getMessage());
        }
    }

    /**
     * Lists all loaded JAR files.
     *
     * @return ActionResult with the list of loaded JARs
     */
    private ActionResult listLoadedJars() {
        if (loadedJars.isEmpty()) {
            return new ActionResult(true, "No JARs loaded");
        }
        return new ActionResult(true,
            "Loaded JARs: " + String.join(", ", loadedJars.keySet()));
    }

    /**
     * Loads an actor from an external JAR file (legacy one-step method).
     *
     * <p>Arguments format (YAML array, recommended):</p>
     * <pre>{@code
     * arguments: ["plugins/my-actor.jar", "com.example.MyActor", "myactor"]
     * }</pre>
     *
     * <p>Legacy format (comma-separated string) is also supported for backward compatibility:</p>
     * <pre>{@code
     * arguments: "plugins/my-actor.jar,com.example.MyActor,myactor"
     * }</pre>
     *
     * <p><strong>Note:</strong> This method registers the actor at the top level
     * (no parent). For proper actor tree placement, use {@code loadJar} followed
     * by {@code createChild}.</p>
     *
     * @param args JSON array or comma-separated string: [jarPath, className, actorName]
     * @return ActionResult indicating success or failure
     */
    private ActionResult loadFromJar(String args) {
        try {
            String jarPath;
            String className;
            String actorName;

            // Try to parse as JSON array first
            if (args.trim().startsWith("[")) {
                JSONArray jsonArray = new JSONArray(args);
                if (jsonArray.length() < 3) {
                    return new ActionResult(false,
                        "Invalid args. Expected array: [jarPath, className, actorName]");
                }
                jarPath = jsonArray.getString(0).trim();
                className = jsonArray.getString(1).trim();
                actorName = jsonArray.getString(2).trim();
            } else {
                // Fall back to legacy comma-separated format
                String[] parts = args.split(",", 3);
                if (parts.length < 3) {
                    return new ActionResult(false,
                        "Invalid args. Expected: jarPath,className,actorName");
                }
                jarPath = parts[0].trim();
                className = parts[1].trim();
                actorName = parts[2].trim();
            }

            // Resolve Maven coordinate if applicable
            jarPath = resolveMavenCoordinate(jarPath);

            // Load actor using DynamicActorLoader
            Path jar = Paths.get(jarPath);

            // Also store ClassLoader for potential reuse by createChild
            if (!loadedJars.containsKey(jarPath)) {
                URL jarUrl = jar.toUri().toURL();
                URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{jarUrl},
                    getClass().getClassLoader()
                );
                loadedJars.put(jarPath, classLoader);
            }

            Object actor = DynamicActorLoader.loadActor(jar, className, actorName);

            // Wrap as IIActorRef if it implements CallableByActionName
            if (actor instanceof CallableByActionName) {
                // Inject ActorSystem if the plugin implements ActorSystemAware
                if (actor instanceof ActorSystemAware) {
                    ((ActorSystemAware) actor).setActorSystem(system);
                }

                GenericIIAR<?> actorRef = new GenericIIAR<>(actorName, actor, system);

                // Inject IIActorRef if the plugin implements IIActorRefAware
                if (actor instanceof IIActorRefAware) {
                    ((IIActorRefAware) actor).setIIActorRef(actorRef);
                }

                system.addIIActor(actorRef);

                return new ActionResult(true,
                    "Loaded and registered actor: " + actorName +
                    " from JAR: " + jarPath);
            } else {
                return new ActionResult(false,
                    "Actor must implement CallableByActionName for workflow use");
            }

        } catch (Exception e) {
            return new ActionResult(false, "Failed to load from JAR: " + e.getMessage());
        }
    }

    /**
     * Registers actors from a ServiceLoader ActorProvider.
     *
     * <p>Arguments format (YAML array):</p>
     * <pre>{@code
     * arguments: ["providerName"]
     * }</pre>
     *
     * <p>This calls the provider's registerActors() method to register
     * all actors provided by that plugin.</p>
     *
     * @param args JSON array or plain string containing the provider name
     * @return ActionResult indicating success or failure
     */
    private ActionResult createFromProvider(String args) {
        try {
            String providerName;

            // Try to parse as JSON array first
            if (args.trim().startsWith("[")) {
                JSONArray jsonArray = new JSONArray(args);
                if (jsonArray.length() < 1) {
                    return new ActionResult(false,
                        "Invalid args. Expected array: [providerName]");
                }
                providerName = jsonArray.getString(0).trim();
            } else {
                providerName = args.trim();
            }

            // Find provider using ServiceLoader
            ServiceLoader<ActorProvider> loader = ServiceLoader.load(ActorProvider.class);
            ActorProvider targetProvider = null;

            for (ActorProvider provider : loader) {
                if (provider.getPluginName().equals(providerName)) {
                    targetProvider = provider;
                    break;
                }
            }

            if (targetProvider == null) {
                return new ActionResult(false,
                    "Provider not found: " + providerName);
            }

            // Let provider register its actors
            targetProvider.registerActors(system);

            return new ActionResult(true,
                "Registered actors from provider: " + providerName);

        } catch (Exception e) {
            return new ActionResult(false, "Failed to create from provider: " + e.getMessage());
        }
    }

    /**
     * Lists all available ActorProvider instances.
     *
     * @return ActionResult with provider list
     */
    private ActionResult listProviders() {
        try {
            ServiceLoader<ActorProvider> loader = ServiceLoader.load(ActorProvider.class);
            List<String> providerNames = new ArrayList<>();

            for (ActorProvider provider : loader) {
                providerNames.add(provider.getPluginName());
            }

            if (providerNames.isEmpty()) {
                return new ActionResult(true, "No providers found");
            }

            return new ActionResult(true,
                "Available providers: " + String.join(", ", providerNames));

        } catch (Exception e) {
            return new ActionResult(false, "Failed to list providers: " + e.getMessage());
        }
    }

    /**
     * Loads ActorProvider plugins from an external JAR file.
     *
     * <p>Arguments format (YAML array):</p>
     * <pre>{@code
     * arguments: ["plugins/my-providers.jar"]
     * }</pre>
     *
     * <p>The JAR must contain provider implementations registered in
     * META-INF/services/com.scivicslab.turingworkflow.ActorProvider</p>
     *
     * @param args JSON array or plain string containing the JAR path
     * @return ActionResult with loaded provider names
     */
    private ActionResult loadProvidersFromJar(String args) {
        try {
            String jarPath;

            // Try to parse as JSON array first
            if (args.trim().startsWith("[")) {
                JSONArray jsonArray = new JSONArray(args);
                if (jsonArray.length() < 1) {
                    return new ActionResult(false,
                        "Invalid args. Expected array: [jarPath]");
                }
                jarPath = jsonArray.getString(0).trim();
            } else {
                jarPath = args.trim();
            }

            // Resolve Maven coordinate if applicable
            jarPath = resolveMavenCoordinate(jarPath);

            Path path = Paths.get(jarPath);
            URL jarUrl = path.toUri().toURL();

            // Create new ClassLoader for the plugin JAR (or reuse if already loaded)
            URLClassLoader classLoader = loadedJars.get(jarPath);
            if (classLoader == null) {
                classLoader = new URLClassLoader(
                    new URL[]{jarUrl},
                    getClass().getClassLoader()
                );
                loadedJars.put(jarPath, classLoader);
            }

            // Load providers from the JAR using ServiceLoader
            ServiceLoader<ActorProvider> loader =
                ServiceLoader.load(ActorProvider.class, classLoader);

            List<String> loadedProviders = new ArrayList<>();

            for (ActorProvider provider : loader) {
                loadedProviders.add(provider.getPluginName());
            }

            if (loadedProviders.isEmpty()) {
                return new ActionResult(false,
                    "No ActorProvider implementations found in JAR: " + jarPath);
            }

            String result = "Loaded " + loadedProviders.size() + " provider(s): " +
                          String.join(", ", loadedProviders);
            return new ActionResult(true, result);

        } catch (Exception e) {
            return new ActionResult(false, "Failed to load JAR: " + e.getMessage());
        }
    }

    /**
     * Generic IIActorRef wrapper for dynamically loaded actors.
     *
     * @param <T> the actor type
     */
    private static class GenericIIAR<T> extends IIActorRef<T> {
        private static final Logger LOGGER = Logger.getLogger(GenericIIAR.class.getName());
        private static final String CLASS_NAME = GenericIIAR.class.getName();

        public GenericIIAR(String actorName, T object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            LOGGER.entering(CLASS_NAME, "callByActionName", new Object[]{actionName, args});

            // Log ClassLoader information at FINER level
            LOGGER.logp(Level.FINER, CLASS_NAME, "callByActionName",
                "object class={0}, object classLoader={1}",
                new Object[]{
                    object != null ? object.getClass().getName() : "null",
                    object != null ? object.getClass().getClassLoader() : "null"
                });
            LOGGER.logp(Level.FINER, CLASS_NAME, "callByActionName",
                "CallableByActionName.class classLoader={0}",
                CallableByActionName.class.getClassLoader());

            boolean isCallable = object instanceof CallableByActionName;
            LOGGER.logp(Level.FINER, CLASS_NAME, "callByActionName",
                "instanceof CallableByActionName = {0}", isCallable);

            if (isCallable) {
                ActionResult result = ((CallableByActionName) object).callByActionName(actionName, args);
                LOGGER.exiting(CLASS_NAME, "callByActionName", result);
                return result;
            }
            ActionResult failResult = new ActionResult(false, "Actor does not implement CallableByActionName");
            LOGGER.exiting(CLASS_NAME, "callByActionName", failResult);
            return failResult;
        }
    }
}
