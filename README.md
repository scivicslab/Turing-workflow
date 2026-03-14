# Turing-workflow

**Official Website: [scivicslab.com/docs/turing-workflow](https://scivicslab.com/docs/turing-workflow/introduction)**

A YAML-based workflow engine built on POJO-actor. Provides a Turing-complete state machine interpreter with dynamic actor loading, subworkflows, YAML overlays, and distributed execution.

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Javadoc](https://img.shields.io/badge/javadoc-3.0.0-brightgreen.svg)](https://javadoc.io/doc/com.scivicslab/turing-workflow/3.0.0)
[![Maven Central](https://img.shields.io/maven-central/v/com.scivicslab/turing-workflow.svg)](https://central.sonatype.com/artifact/com.scivicslab/turing-workflow)

## Overview

In the traditional actor model, actors are passive entities — they wait for messages and react to them. While this simplifies concurrent programming by eliminating locks, actors themselves don't decide what to do next; they only respond to external stimuli.

Turing-workflow changes this. By attaching a workflow to an actor, you give it complex behavioral patterns: conditional branching, loops, and state-driven decisions. The actor becomes an agent — an autonomous entity that observes its environment and acts according to its own logic.

With Virtual Threads since JDK 21, you can create tens of thousands of such autonomous agents. This combination — complex behavior per actor, massive scale — was impractical before and opens up new applications: large-scale agent-based simulations, infrastructure platforms that monitor and self-repair, AI agent pipelines, and more.

> An agent is anything that can be viewed as perceiving its environment through sensors and acting upon that environment through actuators.
> — Russell & Norvig, "Artificial Intelligence: A Modern Approach"

## Requirements

- Java 21 or higher
- Maven 3.6+
- POJO-actor 3.0.0

## Installation

### Maven

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>turing-workflow</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Building from Source

```bash
git clone https://github.com/scivicslab/Turing-workflow
cd Turing-workflow
mvn install
```

## Workflow Format

Because the workflow is essentially a Turing machine, conditional branching and loops are expressed as state transitions. Each step is simply "send this message to this actor" — just three elements: `actor`, `method`, and `arguments`:

```yaml
name: my-workflow
steps:
  - states: ["0", "1"]
    actions:
      - actor: dataProcessor    # actor name
        method: process         # method name
        arguments: "data.csv"   # arguments
  - states: ["1", "end"]
    actions:
      - actor: log
        method: info
        arguments: "Done"
```

This follows the same mental model as `tell()`/`ask()` in Java code. The combination allows complex logic that traditional YAML-based workflow languages struggle with — without introducing custom syntax.

### Arguments

The `arguments` field accepts:

- **String**: `arguments: "hello"`
- **JSON array**: `arguments: ["ROOT", "myActor", "com.example.MyActor"]`
- **JSON object**: `arguments: {"key": "value"}`

### Conditional Branching

Multiple transitions from the same state provide conditional branching. Transitions are checked in order; the first one whose actions all succeed wins:

```yaml
# From state "2": if current value is "1", stay in state "2"
- states: ["2", "2"]
  actions:
    - actor: turing
      method: matchCurrentValue
      arguments: "1"

# From state "2": if current value is "0", go to state "3"
- states: ["2", "3"]
  actions:
    - actor: turing
      method: matchCurrentValue
      arguments: "0"
```

### State Pattern Matching

- `"!end"` — matches any state that is not `"end"` (useful as a catch-all)

## Example: Turing Machine

The following is a Turing machine that outputs an irrational number: 001011011101111011111...

![](Turing87.jpg)

> — Charles Petzold, "The Annotated Turing", Wiley Publishing, Inc. (2008) page 87.

```yaml
name: turing87
steps:
- states: ["0", "100"]
  actions:
  - {actor: turing, method: initMachine}
- states: ["100", "1"]
  actions:
  - {actor: turing, method: printTape}
- states: ["1", "2"]
  actions:
  - {actor: turing, method: put, arguments: "e"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "e"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "0"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "0"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["101", "2"]
  actions:
  - {actor: turing, method: printTape}
- states: ["2", "2"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "1"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "x"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["2", "3"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "0"}
- states: ["3", "3"]
  actions:
  - {actor: turing, method: isAny}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: move, arguments: "R"}
- states: ["3", "4"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: put, arguments: "1"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["4", "3"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "x"}
  - {actor: turing, method: put, arguments: " "}
  - {actor: turing, method: move, arguments: "R"}
- states: ["4", "5"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "e"}
  - {actor: turing, method: move, arguments: "R"}
- states: ["4", "4"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["5", "5"]
  actions:
  - {actor: turing, method: isAny}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: move, arguments: "R"}
- states: ["5", "101"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: put, arguments: "0"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
```

## Java API

```java
// Create IIActorSystem
IIActorSystem system = new IIActorSystem("my-workflow");
system.addIIActor(new MyActor("myActor", system));

// Build and run interpreter
Interpreter interpreter = new Interpreter.Builder()
    .loggerName("my-workflow")
    .team(system)
    .build();

interpreter.readYaml(new FileInputStream("workflow.yaml"));
ActionResult result = interpreter.runUntilEnd(100);

System.out.println("Result: " + result.getResult());
```

### Writing an Actor

Implement `IIActorRef<T>` and annotate methods with `@Action`:

```java
public class MyActor extends IIActorRef<MyActor> {

    public MyActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    @Action("process")
    public ActionResult process(String filename) {
        // ... process the file
        return new ActionResult(true, "Processed: " + filename);
    }
}
```

The `@Action` annotation value is the method name used in YAML. Returning `new ActionResult(false, ...)` causes the transition to fail and the interpreter tries the next transition.

## Dynamic Actor Loading

Actors can be loaded at runtime from external JARs (including Maven coordinates):

```yaml
steps:
  - states: ["0", "1"]
    actions:
      - actor: loader
        method: loadJar
        arguments: "com.example:my-plugin:1.0.0"

  - states: ["1", "2"]
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "myPlugin", "com.example.MyPluginActor"]
```

## Feature List

- **YAML Workflow** — Define workflows in YAML format
- **Turing-complete** — Conditional branching and loops via state transitions
- **`@Action` annotation** — Simple method-level action registration
- **Dynamic Actor Loading** — Load actors from external JARs at runtime via Maven coordinates
- **Subworkflows** — Split and reuse workflow definitions
- **YAML Overlay** — Environment-specific configuration (dev/staging/prod)
- **Distributed Execution** — Inter-node workflow execution
- **Breakpoints** — Pause/resume workflow execution
- **CLI** — Command-line interface for workflow execution

## References

- **Javadoc**: [API Reference](https://javadoc.io/doc/com.scivicslab/turing-workflow/3.0.0)
- **POJO-actor**: [GitHub](https://github.com/scivicslab/POJO-actor)
- **Turing-workflow-plugins**: [GitHub](https://github.com/scivicslab/Turing-workflow-plugins)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
