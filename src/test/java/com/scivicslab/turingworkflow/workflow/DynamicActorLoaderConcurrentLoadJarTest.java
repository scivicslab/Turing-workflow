/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scivicslab.turingworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.action.CallableByActionName;

/**
 * Verifies that two {@code loadJar} calls made at the same time both stay reachable: after them,
 * {@code createChild} finds a class from each jar.
 *
 * <p>The first jar loaded makes the shared class loader. Two loads that both find no loader yet
 * would each make one, and the loader made first would be dropped with its jar. To make that
 * happen on every run, the loader is made through a factory that holds each caller at a barrier
 * until the other arrives. A loader that lets only one caller in at a time sends the first through
 * after the barrier's timeout, and the second then adds its jar to the loader the first made.</p>
 */
@DisplayName("loader.loadJar: two jars loaded at the same time both stay reachable")
class DynamicActorLoaderConcurrentLoadJarTest {

    /** How long a caller waits at the barrier for the other one. */
    private static final long BARRIER_SECONDS = 2;

    @TempDir
    Path dir;

    private IIActorSystem system;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("loader-concurrent-load-jar-test");
        system.addIIActor(new IIActorRef<Object>("parent", new Object(), system) {
            @Override
            public ActionResult callByActionName(String actionName, String args) {
                return new ActionResult(true, "parent");
            }
        });
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
        system.terminate();
    }

    /** A loader whose first shared class loader is made only once two callers are inside. */
    private static final class InterleavingLoader extends DynamicActorLoaderActor {
        final CyclicBarrier bothInside = new CyclicBarrier(2);
        final AtomicInteger made = new AtomicInteger();

        InterleavingLoader(IIActorSystem system) {
            super(system);
        }

        @Override
        SharedClassLoader newSharedClassLoader(URL jarUrl) {
            made.incrementAndGet();
            try {
                bothInside.await(BARRIER_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException e) {
                // The other caller could not get in: the loader is made by one caller at a time.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.newSharedClassLoader(jarUrl);
        }
    }

    /** Compiles one actor class that is on no classpath, and puts it in a jar of its own. */
    private Path jarWith(String pkg, String simpleName) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src-" + simpleName).resolve(pkg));
        Path java = src.resolve(simpleName + ".java");
        Files.writeString(java, """
                package %s;

                import com.scivicslab.pojoactor.action.ActionResult;
                import com.scivicslab.pojoactor.action.CallableByActionName;

                public class %s implements CallableByActionName {
                    @Override
                    public ActionResult callByActionName(String actionName, String args) {
                        return new ActionResult(true, "%s");
                    }
                }
                """.formatted(pkg, simpleName, simpleName));
        Path classes = Files.createDirectories(dir.resolve("classes-" + simpleName));
        String classpath = Path.of(CallableByActionName.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int status = javac.run(null, null, null, "-cp", classpath, "-d", classes.toString(), java.toString());
        assertEquals(0, status, "the test class must compile");

        Path jar = dir.resolve(simpleName + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out);
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jarOut.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jarOut.write(Files.readAllBytes(file));
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    @Test
    @DisplayName("createChild finds a class from each of two jars loaded at the same time")
    void bothJarsStayReachable() throws Exception {
        Path jarA = jarWith("t283a", "ActorA");
        Path jarB = jarWith("t283b", "ActorB");
        InterleavingLoader loader = new InterleavingLoader(system);

        // Two real (platform) threads call loadJar at the same moment.
        List<Path> jars = List.of(jarA, jarB);
        AtomicReferenceArray<ActionResult> loaded = new AtomicReferenceArray<>(2);
        CyclicBarrier start = new CyclicBarrier(2);
        Thread[] callers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int n = i;
            callers[i] = Thread.ofPlatform().name("load-jar-" + n).start(() -> {
                try {
                    start.await();
                    loaded.set(n, loader.callByActionName("loadJar", jars.get(n).toString()));
                } catch (Exception e) {
                    loaded.set(n, new ActionResult(false, e.toString()));
                }
            });
        }
        for (Thread caller : callers) {
            caller.join(TimeUnit.SECONDS.toMillis(30));
        }

        for (int i = 0; i < 2; i++) {
            assertTrue(loaded.get(i) != null && loaded.get(i).isSuccess(), "loadJar " + jars.get(i) + ": " + loaded.get(i));
        }
        ActionResult a = loader.callByActionName("createChild", "[\"parent\", \"a\", \"t283a.ActorA\"]");
        ActionResult b = loader.callByActionName("createChild", "[\"parent\", \"b\", \"t283b.ActorB\"]");
        assertTrue(a.isSuccess() && b.isSuccess(), "both jars must stay reachable; shared class loaders made: "
                + loader.made.get() + "; ActorA: " + a.getResult() + "; ActorB: " + b.getResult());
    }
}
