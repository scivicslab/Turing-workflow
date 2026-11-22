package com.scivicslab.pojoactor.action.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The prose about actions that {@code TuringPluginDoclet} wrote at build time, one file per class, at
 * {@value #RESOURCE_DIR}{@code <binary class name>.json}: for every {@code @Action} method, the first
 * sentence of its Javadoc, the rest of the body, the first {@code <pre>} block as the usage example, and
 * the {@code @param} descriptions — of its argument record, or of the method's own String parameter.
 * Keyed by fully-qualified class name and action name, the same key the JSON Schemas use, so
 * {@link ActionCatalog} can answer both in one description ({@code ActionCatalogWithJavadoc_260930_oo01}).
 *
 * <p>A class's file is read the first time the class is asked about, through the class's own class
 * loader, so a jar loaded while the process runs is read like one on the startup classpath. Nothing is
 * listed or indexed: whoever asks already knows the class, from a registered actor. The file name is the
 * class's, so in an uber-jar no two jars' files collide.</p>
 */
public class ActionManifest {

    private static final Logger logger = Logger.getLogger(ActionManifest.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The resource directory the per-class manifests are in. */
    public static final String RESOURCE_DIR = "META-INF/turing-actions/";

    /** The resource holding the manifest of the class named {@code binaryClassName}. */
    public static String resourceOf(String binaryClassName) {
        return RESOURCE_DIR + binaryClassName + ".json";
    }

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
    /** Class names whose manifest was looked for, found or not, so each is read at most once. */
    private final Set<String> looked = ConcurrentHashMap.newKeySet();
    private final ClassLoader classLoader;

    /** Looks up a class named by a string through this class's class loader. */
    public ActionManifest() {
        this(ActionManifest.class.getClassLoader());
    }

    /**
     * @param classLoader where {@link #docFor(String, String)} looks up a class named by a string;
     *                    {@link #docFor(Class, String)} uses the class's own loader
     */
    public ActionManifest(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** Reads the manifest of {@code className} through {@code loader}, once. */
    private void ensureRead(String className, ClassLoader loader) {
        if (loader == null || !looked.add(className)) return;
        String resource = resourceOf(className);
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in != null) load(JSON.readTree(in));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read action manifest " + resource, e);
        }
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
            ActionDoc doc = docOf(c, actionName);
            if (doc != null) return doc;
            for (Class<?> i : c.getInterfaces()) {
                doc = docOf(i, actionName);
                if (doc != null) return doc;
            }
        }
        return null;
    }

    private ActionDoc docOf(Class<?> c, String actionName) {
        ensureRead(c.getName(), c.getClassLoader());
        return docs.get(c.getName() + "." + actionName);
    }

    /**
     * What the manifest of the class named {@code className} (its binary name) says about the action,
     * read through the loader this manifest was made with; the class itself only, not its supertypes.
     */
    public ActionDoc docFor(String className, String actionName) {
        ensureRead(className, classLoader);
        return docs.get(className + "." + actionName);
    }
}
