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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;

import com.scivicslab.pluggablecli.CommandRepository;
import com.scivicslab.pluggablecli.PluginLoader;

/**
 * Main CLI entry point for POJO-actor workflow interpreter.
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar pojo-actor-3.0.0-SNAPSHOT.jar run -d ./ -w hello.yaml
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class WorkflowCLI {

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        CommandRepository cmds = new CommandRepository();
        setupCommands(cmds);

        // Load plugins via ServiceLoader
        PluginLoader loader = new PluginLoader(cmds);
        loader.loadPlugins();

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
    }
}
