package com.scivicslab.turingworkflow.workflow;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Runs {@code workflows/t250-recursive-call.yaml}, which calls itself, and prints how the run ended.
 * With the argument {@code uncapped} the sub-workflow depth limit is raised far above the stack, so
 * the recursion runs until the real stack runs out ({@link ActionErrorEscapeTest}); with
 * {@code capped} the default limit stops it ({@link InterpreterLimitsTest}). It is started in a JVM
 * of its own, because running out of the stack can leave call sites of that JVM unusable for every
 * later test.
 */
public final class RecursiveCallMain {

    private RecursiveCallMain() {}

    /** The system property that sets the sub-workflow depth limit. */
    static final String MAX_CALL_DEPTH_PROPERTY = "turingworkflow.maxCallDepth";

    public static void main(String[] args) {
        if (args.length == 0 || !"capped".equals(args[0])) {
            System.setProperty(MAX_CALL_DEPTH_PROPERTY, "1000000");
        }
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
            System.out.println("TEXT " + result.getResult());
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
        System.exit(0);
    }
}
