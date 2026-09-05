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

package com.scivicslab.turingworkflow.cli;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;

import com.scivicslab.pluggablecli.CommandRepository;
import com.scivicslab.pluggablecli.PluginLoader;

/**
 * Application entry point: the {@code main} method that drives the pluggable-cli
 * command dispatcher for the Turing-workflow interpreter.
 *
 * <p>This class is intentionally thin and defines no command-line options itself.
 * Its {@code main} method only:</p>
 * <ol>
 *   <li>creates a {@link CommandRepository},</li>
 *   <li>asks each per-command class to register its own options into that repository
 *       (see {@link #setupCommands}, which calls {@link RunCLI#registerCommand}),</li>
 *   <li>loads any classpath plugins via {@link PluginLoader}, and</li>
 *   <li>parses the arguments and dispatches to the matching command, or prints the
 *       command list / per-command help.</li>
 * </ol>
 *
 * <p>The actual command-line option definitions are not declared here. They are factored
 * out into separate, per-command classes (for example {@link RunCLI}) and registered into
 * the repository. Keeping the option definitions out of {@code main} is a deliberate
 * structuring choice: otherwise this entry point would grow into one huge method as more
 * subcommands are added. Each subcommand owns the structured definition of its own options;
 * this class only wires them together and dispatches.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar turing-workflow-&lt;version&gt;.jar run -d ./ -w hello.yaml
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class App {

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        CommandRepository cmds = new CommandRepository();
        setupCommands(cmds);

        // Load plugins. First any bundled on the classpath, then external plugin
        // JARs listed in the plugins config file (see PluginsConfig).
        PluginLoader loader = new PluginLoader(cmds);
        loader.loadPlugins();
        loadConfiguredPlugins(loader);

        try {
            CommandLine cl = cmds.parse(args);
            String command = cmds.getGivenCommand();

            if (command == null) {
                cmds.printCommandList("pojo-actor <command> [options]");
            } else if (cmds.isHelpRequested()) {
                cmds.printCommandHelp(command);
            } else if (cmds.hasCommand(command)) {
                cmds.execute(command, cl);
            } else {
                System.err.println("Error: Unknown command: " + command);
                cmds.printCommandList("pojo-actor <command> [options]");
                System.exit(1);
            }
        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Registers all built-in commands.
     *
     * @param cmds the command repository
     */
    private static void setupCommands(CommandRepository cmds) {
        RunCLI.registerCommand(cmds);
        ListWorkflowsCLI.registerCommand(cmds);
        DescribeCLI.registerCommand(cmds);
    }

    /**
     * Loads external {@code CliPlugin} JARs listed in the plugins config file
     * (see {@link PluginsConfig}) by building a {@link URLClassLoader} over them and
     * handing it to the {@link PluginLoader}. The class loader's parent is this class's
     * own loader so that the {@code CliPlugin} interface (bundled in the uber-jar)
     * resolves to the same type the plugins were compiled against. JARs that do not
     * exist are skipped with a warning so that one missing plugin does not stop startup.
     *
     * @param loader the plugin loader bound to the command repository
     */
    private static void loadConfiguredPlugins(PluginLoader loader) {
        List<Path> jars = PluginsConfig.load();
        if (jars.isEmpty()) {
            return;
        }
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                System.err.println("Plugin JAR not found, skipping: " + jar);
                continue;
            }
            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                System.err.println("Skipping plugin JAR with invalid path: " + jar);
            }
        }
        if (urls.isEmpty()) {
            return;
        }
        URLClassLoader pluginClassLoader =
                new URLClassLoader(urls.toArray(new URL[0]), App.class.getClassLoader());
        loader.loadPlugins(pluginClassLoader);
    }
}
