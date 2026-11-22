package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Runs {@code workflows/t250-recursive-call.yaml}, which calls itself until the stack runs out, and
 * prints how the run ended. {@link ActionErrorEscapeTest} starts it in a JVM of its own, because
 * running out of the stack can leave call sites of that JVM unusable for every later test.
 */
public final class RecursiveCallMain {

    private RecursiveCallMain() {}

    public static void main(String[] args) {
        IIActorSystem system = new IIActorSystem("t250-recursive-call", 2);
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("t250").team(system).build();
            InterpreterIIAR self = new InterpreterIIAR("main", interpreter, system);
            interpreter.setSelfActorRef(self);
            system.addIIActor(self);
            interpreter.readYaml(RecursiveCallMain.class.getResourceAsStream("/workflows/t250-recursive-call.yaml"));
            ActionResult result;
            try {
                result = interpreter.runUntilEnd(10);
            } catch (Throwable escaped) {
                System.out.println("ESCAPED");
                escaped.printStackTrace(System.out);
                System.exit(2);
                return;
            }
            System.out.println("RESULT success=" + result.isSuccess());
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
        System.exit(0);
    }
}
