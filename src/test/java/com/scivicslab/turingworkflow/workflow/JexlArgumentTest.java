package com.scivicslab.turingworkflow.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that an argument written as an expression reaches the action with its type intact.
 *
 * <p>The mechanism it replaces, {@code $(actor.method)}, answers through {@code ActionResult},
 * whose value is text. An action declaring a numeric argument cannot be called that way.</p>
 */
@DisplayName("jexl: arguments")
class JexlArgumentTest {

    @Test
    @DisplayName("a number computed by one actor arrives at another as a number")
    void numberReachesActionAsNumber(@TempDir Path dir) throws Exception {
        Path wf = dir.resolve("jexl-args.yaml");
        Files.writeString(wf, """
            name: jexl-args
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: calc:source
                    method: set
                    arguments:
                      value: 7
              - states: ["1", "2"]
                actions:
                  - actor: calc:target
                    method: set
                    arguments:
                      value: 0
              - states: ["2", "end"]
                actions:
                  - actor: calc:target
                    method: add
                    arguments:
                      operand: "jexl: actors.get('calc:source').get()"
            """);

        IIActorSystem system = new IIActorSystem("jexl-args-test");
        try {
            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("jexl-args-test")
                    .team(system)
                    .build();
            interpreter.readYaml(wf);
            interpreter.runUntilEnd(100);

            assertEquals("7", system.getIIActor("calc:target")
                                    .callByActionName("get", "").getResult(),
                         "the operand must arrive as the number 7, not as unusable text");
        } finally {
            system.terminateIIActors();
        }
    }
}
