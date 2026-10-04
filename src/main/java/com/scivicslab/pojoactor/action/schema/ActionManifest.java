package com.scivicslab.pojoactor.action.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The prose about actions that {@code TuringPluginDoclet} wrote at build time into each jar's
 * {@code META-INF/turing-plugin.json}: for every {@code @Action} method, the first sentence of its
 * Javadoc, the rest of the body, the first {@code <pre>} block as the usage example, and the
 * {@code @param} descriptions — of its argument record, or of the method's own String parameter.
 * Read from every jar on the classpath, keyed by fully-qualified class name and action name, the same
 * key the JSON Schemas use, so {@link ActionCatalog} can answer both in one description
 * ({@code ActionCatalogWithJavadoc_260930_oo01}).
 */
public class ActionManifest {

    private static final Logger logger = Logger.getLogger(ActionManifest.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The resource every jar carries when its build ran the doclet. */
    public static final String DEFAULT_RESOURCE = "META-INF/turing-plugin.json";

    /**
     * What the manifest says about one action.
     *
     * @param description the first sentence of the action method's Javadoc; "" when it has none
     * @param details     the Javadoc body after the first sentence, without its {@code <pre>} blocks; "" when none
     * @param example     the first {@code <pre>} block of the Javadoc body, the author's usage example; "" when none
     * @param params      field name to its description, in declaration order: the argument record's
     *                    components, or the method's own parameter for a raw-String action; empty when none
     * @param argsFormat  the doclet's guess at the argument form ({@code string}, {@code object}, ...)
     */
    public record ActionDoc(String description, String details, String example,
                            Map<String, String> params, String argsFormat) {}

    private final Map<String, ActionDoc> docs = new ConcurrentHashMap<>();

    /** Reads every {@value #DEFAULT_RESOURCE} visible to this class's classloader. */
    public ActionManifest() {
        this(ActionManifest.class.getClassLoader(), DEFAULT_RESOURCE);
    }

    /** Reads every {@value #DEFAULT_RESOURCE} visible to {@code classLoader}. */
    public ActionManifest(ClassLoader classLoader) {
        this(classLoader, DEFAULT_RESOURCE);
    }

    /**
     * @param classLoader  where to look
     * @param resourceName the resource name to read, for tests that keep a manifest apart
     */
    public ActionManifest(ClassLoader classLoader, String resourceName) {
        addFrom(classLoader, resourceName);
    }

    /** Reads every {@code resourceName} visible to {@code classLoader}; returns how many actions were added. */
    public int addFrom(ClassLoader classLoader, String resourceName) {
        int before = docs.size();
        try {
            Enumeration<URL> urls = classLoader.getResources(resourceName);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream in = url.openStream()) {
                    load(JSON.readTree(in));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Could not read action manifest " + url, e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not list action manifests " + resourceName, e);
        }
        return docs.size() - before;
    }

    private void load(JsonNode root) {
        for (JsonNode actor : root.path("actors")) {
            String className = actor.path("class").asText("");
            if (className.isEmpty()) continue;
            for (JsonNode action : actor.path("actions")) {
                String name = action.path("name").asText("");
                if (name.isEmpty()) continue;
                Map<String, String> params = new LinkedHashMap<>();
                for (JsonNode p : action.path("params")) {
                    params.put(p.path("name").asText(""), p.path("description").asText(""));
                }
                docs.putIfAbsent(className + "." + name, new ActionDoc(
                        action.path("description").asText(""),
                        action.path("details").asText(""),
                        action.path("example").asText(""),
                        Collections.unmodifiableMap(params),
                        action.path("argsFormat").asText("")));
            }
        }
    }

    /**
     * What the manifest says about the action, looked up on the class, then on its superclasses and
     * interfaces: an {@code @Action} method is dispatched wherever in the hierarchy it is declared,
     * and the doclet wrote its prose under the declaring class.
     *
     * @return the doc, or null when no jar documented the action anywhere in the hierarchy
     */
    public ActionDoc docFor(Class<?> actorClass, String actionName) {
        for (Class<?> c = actorClass; c != null && c != Object.class; c = c.getSuperclass()) {
            ActionDoc doc = docFor(c.getName(), actionName);
            if (doc != null) return doc;
            for (Class<?> i : c.getInterfaces()) {
                doc = docFor(i.getName(), actionName);
                if (doc != null) return doc;
            }
        }
        return null;
    }

    /** Same as {@link #docFor(Class, String)}, keyed by the class's fully-qualified name. */
    public ActionDoc docFor(String className, String actionName) {
        return docs.get(className + "." + actionName);
    }

    /** The number of documented actions. */
    public int size() {
        return docs.size();
    }
}
