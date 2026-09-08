[![Sponsor](https://img.shields.io/github/sponsors/scivicslab)](https://github.com/sponsors/scivicslab)

# Turing-workflow

**Official Website: [scivicslab.com/docs/turing-workflow](https://scivicslab.com/docs/turing-workflow/introduction)**

A YAML-based workflow engine built on POJO-actor. Provides a Turing-complete state machine interpreter with dynamic actor loading, subworkflows, YAML overlays, and distributed execution.

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Javadoc](https://img.shields.io/badge/javadoc-4.1.0-brightgreen.svg)](https://javadoc.io/doc/com.scivicslab/turing-workflow/4.1.0)
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
- POJO-actor 4.1.0

## Installation

### Maven

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>turing-workflow</artifactId>
    <version>4.1.0</version>
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
        arguments:              # one named field per argument
          file: "data.csv"
  - states: ["1", "end"]
    actions:
      - actor: log
        method: info
        arguments:
          message: "Done"
```

This follows the same mental model as `tell()`/`ask()` in Java code. The combination allows complex logic that traditional YAML-based workflow languages struggle with — without introducing custom syntax.

### Arguments

An action states what it takes by declaring `argsType` (see [Writing an Actor](#writing-an-actor)), and `arguments` names one field per component of that type:

```yaml
- actor: turing
  method: put
  arguments:
    value: "e"
```

The names come from the record the action declares, so a caller can be told the shape rather than having to read the method. A JSON Schema is generated for each one at build time, and the argument is checked against it before the action runs.

An action that declares no `argsType` receives the raw string the engine built: a scalar is wrapped in a one-element JSON array, and a list becomes a JSON array of its elements.

### Expressions

A value that starts with `jexl:` is evaluated as a [JEXL](https://commons.apache.org/proper/commons-jexl/) expression, and the action receives whatever the expression answered — a number stays a number:

```yaml
- actor: calc:total
  method: add
  arguments:
    operand: "jexl: actors.get('calc:i').get()"
```

The expression is given five things:

| name | what it is |
|---|---|
| `actors` | the actors in this system, by name — `actors.get('calc:i')` |
| `state` | this actor's `JsonState` |
| `result` | what the previous action returned |
| `self` | this actor's own name |
| `currentState` | the state the machine is in |

A value that does not start with `jexl:` is passed through as written.

### Conditional Branching

Multiple transitions from the same state provide conditional branching. Transitions are checked in order; the first one whose actions all succeed wins:

```yaml
# From state "2": if current value is "1", stay in state "2"
- states: ["2", "2"]
  actions:
    - actor: turing
      method: matchCurrentValue
      arguments:
        expected: "1"

# From state "2": if current value is "0", go to state "3"
- states: ["2", "3"]
  actions:
    - actor: turing
      method: matchCurrentValue
      arguments:
        expected: "0"
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
  - {actor: turing, method: put, arguments: {value: "e"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: put, arguments: {value: "e"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: put, arguments: {value: "0"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: put, arguments: {value: "0"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
- states: ["101", "2"]
  actions:
  - {actor: turing, method: printTape}
- states: ["2", "2"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: {expected: "1"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: put, arguments: {value: "x"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
- states: ["2", "3"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: {expected: "0"}}
- states: ["3", "3"]
  actions:
  - {actor: turing, method: isAny}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
- states: ["3", "4"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: put, arguments: {value: "1"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
- states: ["4", "3"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: {expected: "x"}}
  - {actor: turing, method: put, arguments: {value: " "}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
- states: ["4", "5"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: {expected: "e"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
- states: ["4", "4"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: move, arguments: {direction: "L"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
- states: ["5", "5"]
  actions:
  - {actor: turing, method: isAny}
  - {actor: turing, method: move, arguments: {direction: "R"}}
  - {actor: turing, method: move, arguments: {direction: "R"}}
- states: ["5", "101"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: put, arguments: {value: "0"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
  - {actor: turing, method: move, arguments: {direction: "L"}}
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

Implement `IIActorRef<T>` and annotate methods with `@Action`. Declare what the action takes as a record, and name it in `argsType`:

```java
public class MyActor extends IIActorRef<MyActor> {

    public MyActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Which file to process.
     *
     * @param file the file's path
     */
    public record ProcessArgs(@NotNull String file) {}

    @Action(value = "process", argsType = ProcessArgs.class)
    public ActionResult process(ProcessArgs args) {
        // ... process the file
        return new ActionResult(true, "Processed: " + args.file());
    }
}
```

The `@Action` value is the name a workflow step writes under `method`, and each record component is a field under `arguments`. `@NotNull` on a component makes that field required in the generated JSON Schema; a component without it may be omitted, and arrives as null.

An action that takes nothing keeps a plain `String` parameter and declares no `argsType`:

```java
@Action("printTape")
public ActionResult printTape(String args) { ... }
```

Returning `new ActionResult(false, ...)` causes the transition to fail and the interpreter tries the next transition.

## Dynamic Actor Loading

Actors can be loaded at runtime from external JARs (including Maven coordinates). `DynamicActorLoaderActor` dispatches on its own string protocol rather than declaring `argsType`, so these two actions take positional values:

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
- **Declared arguments** — An action names the record it takes, so a caller can be told the shape instead of reading the method
- **JEXL expressions** — A `jexl:` value is evaluated and reaches the action with its type intact
- **Name-based dispatch** — `ActionDispatcher` finds the method a workflow step names, and checks its arguments against the JSON Schema generated from the declared `argsType`
- **Dynamic Actor Loading** — Load actors from external JARs at runtime via Maven coordinates
- **Subworkflows** — Split and reuse workflow definitions
- **YAML Overlay** — Environment-specific configuration (dev/staging/prod)
- **Breakpoints** — Pause/resume workflow execution
- **CLI** — Command-line interface for workflow execution

## References

- **Javadoc**: [API Reference](https://javadoc.io/doc/com.scivicslab/turing-workflow/4.1.0)
- **POJO-actor**: [GitHub](https://github.com/scivicslab/POJO-actor)
- **pojo-actor-distributed**: [GitHub](https://github.com/scivicslab/pojo-actor-distributed) — carries messages between actor systems in different processes
- **Turing-workflow-plugins**: [GitHub](https://github.com/scivicslab/Turing-workflow-plugins)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
