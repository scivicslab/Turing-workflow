# Changelog

## 5.0.0 (draft)

Changes since `v4.8.0` (`503bf1e`). Turing-workflow 5.0.0 needs POJO-actor 5.0.0, whose futures now always complete (see POJO-actor's `CHANGELOG.md`). The specification of failed actions is `060_ErrorHandling_260509_oo01`.

The public API gains two methods: `ActionResult.error(Throwable, String)` and `ActionResult.errorText(Throwable, String)`, one static method, `IIActorSystem.removedValueActorReason(String)`, and one class, `com.scivicslab.turingworkflow.workflow.Removal`, which builds every "removed in 5.0.0" reason: `reason(String, String)`, `failure(String, String)`, the constants `TRANSITIONS_KEY` and `ARGUMENT_KEY`, and `refuseTransitionsKey(Map)` and `refuseArgumentKey(Map)`. The built-in value actors `calc`, `list` and `str` are removed, with their classes. The other changes are the texts that a failed action leaves in `result`, and the behaviour below.

### The built-in value actors `calc`, `list` and `str` are removed; `out` stays

Removed classes: `CalcActor`, `ListActor`, `StringActor`, and the value objects they wrapped, `com.scivicslab.turingworkflow.workflow.Accumulator` and `TextSlot`. (The `accumulator` package, which collects log output, is unchanged.)

Up to 4.8.0, naming `calc`, `list` or `str` (plain or with a `:name`, such as `calc:i`) in a workflow made the actor on first use, with a warning. These actors held values from the time before `jexl:` expressions existed. A workflow now keeps its numbers, text and lists in its own state: it writes them with `this.putJson` and `this.appendJson`, and reads them with `jexl: state...`.

Nothing is made under these names any more, not even by a factory registered with `IIActorSystem.addActorFactory`. An action that names one of them is false, and the next transition reads this reason in `jexl: result`:

```
error: the built-in actor 'calc:i' was removed in 5.0.0; keep the value in the workflow's own state instead: write it with this.putJson or this.appendJson, read it with jexl: state... (in calc:i.inc)
```

An argument expression that reaches one of them (`jexl: actors.get('calc:i').get()`) fails its action with `argument expression failed: <expression> -- the built-in actor 'calc:i' was removed in 5.0.0; ...`.

`out` is the supported built-in way to print. It is still made on first use, and it no longer logs a warning when it is.

**What 4.8 YAML must change:** every action on `calc`, `list` or `str`, and every `actors.get(...)` of one of them:

```yaml
# 4.8
  - states: ["0", "loop"]
    actions:
      - actor: calc:i
        method: set
        arguments: "0"
  - states: ["loop", "process"]
    actions:
      - actor: list:items
        method: get
        arguments: {index: "jexl: actors.get('calc:i').get()"}
  - states: ["process", "loop"]
    actions:
      - actor: out
        method: print
        arguments: "jexl: result"
      - actor: calc:i
        method: inc

# 5.0: the list is in the state at items
  - states: ["0", "loop"]
    actions:
      - actor: this
        method: putJson
        arguments: {path: i, value: 0}
  - states: ["loop", "process"]
    actions:
      - actor: this
        method: onlyIf
        arguments: "jexl: state.getInt('i', 0) < state.select('items').size()"
  - states: ["process", "loop"]
    actions:
      - actor: out
        method: print
        arguments: "jexl: state.select('items').get(state.getInt('i', 0)).asText()"
      - actor: this
        method: putJson
        arguments: {path: i, value: "jexl: state.getInt('i', 0) + 1"}
```

| 4.8 | 5.0 |
|---|---|
| `calc:i.set "0"` | `this.putJson {path: i, value: 0}` |
| `calc:i.inc` | `this.putJson {path: i, value: "jexl: state.getInt('i', 0) + 1"}` |
| `jexl: actors.get('calc:i').get()` | `jexl: state.getInt('i', 0)` (`state.getDouble('x', 0)` for a fraction) |
| `list:items.add "a"` | `this.appendJson {path: items, value: "a"}` |
| `list:items.size` | `jexl: state.select('items').size()` |
| `list:items.get {index: n}` | `jexl: state.select('items').get(n).asText()` |
| `str:msg.set "..."` | `this.putJson {path: msg, value: "..."}` |
| `jexl: actors.get('str:msg').get()` | `jexl: state.getString('msg')` |
| `str:body.escapeJsonStored`, pasted into a JSON text | arguments written as a YAML map whose values are `jexl:` expressions, for example `{agent: reviewer, prompt: "jexl: result"}` |
| `str:input` / `str:output` shared with a sub-workflow | `vars.set ["input", ...]` before the call, `jexl: actors.get('vars').get('output')` after it |

**What 4.8 Java must change:** code that constructs `CalcActor`, `ListActor` or `StringActor`, or looks them up with `getIIActor("calc")` and similar, has to keep its values elsewhere. `getIIActor` now returns `null` for these names.

### `loader.loadFromJar` is removed; `loader.loadJar` then `loader.createChild` replaces it

Up to 4.8.0, `loader.loadFromJar` took a jar, a class name and an actor name, and was meant to load the jar and register the actor in one step. From a workflow it did not work. With the array form it added the jar to the loader and then answered `false` (`Actor must implement CallableByActionName for workflow use`), because the object it made was an `ActorRef`, not the plugin object. The comma-separated form reached it as a one-element array, since the engine wraps a single string in an array, and it answered `Invalid args. Expected array: [jarPath, className, actorName]`. Even when it worked, it registered the actor outside the actor tree.

Now the action loads nothing and creates nothing. It is false, and the next transition reads in `jexl: result`:

```
error: the action 'loader.loadFromJar' was removed in 5.0.0; load the jar and create the actor in two steps instead: loader.loadJar, then loader.createChild (in loader.loadFromJar)
```

Every "removed in 5.0.0" reason, this one and the `calc`/`list`/`str` one, is built by the new class `Removal`, so they read alike.

**What 4.8 YAML must change:**

```yaml
# 4.8
  - states: ["0", "1"]
    actions:
      - actor: loader
        method: loadFromJar
        arguments: ["plugins/my-actor.jar", "com.example.MyActor", "myactor"]
      # or: arguments: "plugins/my-actor.jar,com.example.MyActor,myactor"

# 5.0
  - states: ["0", "1"]
    actions:
      - actor: loader
        method: loadJar
        arguments: "plugins/my-actor.jar"
      - actor: loader
        method: createChild
        arguments: ["ROOT", "myactor", "com.example.MyActor"]
```

`createChild` places the actor under a parent (here `ROOT`), so the actor tree shows it and `removeChild` removes it.

The `[actor, action, argument]` list form is unchanged.

### The top-level `transitions:` key is removed; write `steps:`

Up to 4.8.0, a workflow could name its list of steps `transitions:` instead of `steps:`. A workflow whose top level has `transitions:` is now refused when it is loaded, by `Interpreter.readYaml` and by `WorkflowKustomizer.build` (for a base workflow and for a patch alike), with an `IllegalArgumentException` whose message is:

```
the top-level key 'transitions:' was removed in 5.0.0; rename it to 'steps:'
```

A workflow that has both `steps:` and `transitions:` is refused with the same message, rather than one of the two lists being silently ignored.

**What 4.8 YAML must change:**

```yaml
# 4.8
name: my-workflow
transitions:
  - states: ["0", "end"]
    actions:
      - actor: out
        method: print
        arguments: "done"

# 5.0
name: my-workflow
steps:
  - states: ["0", "end"]
    actions:
      - actor: out
        method: print
        arguments: "done"
```

No YAML in `~/works` used the key at the top level.

### An action written with the singular `argument:` key is refused; write `arguments:`

The engine only ever read `arguments:`. Up to 4.8.0, an action written with `argument: "x"` loaded and ran, and its actor was called with `[]`: the value was dropped without a word. A workflow with such an action is now refused when it is loaded, with an `IllegalArgumentException` whose message is:

```
the step key 'argument:' was removed in 5.0.0; write 'arguments:'
```

The check covers every way a workflow is loaded: `Interpreter.readYaml` from a stream or a file, the overlay path `readYaml(Path, Path)`, and the sub-workflows `parallel-map` runs.

**What 4.8 YAML must change:**

```yaml
# 4.8: out.print was called with no arguments
      - actor: out
        method: print
        argument: "done"

# 5.0
      - actor: out
        method: print
        arguments: "done"
```

Of the 1638 YAML files in `~/works`, outside `backup/` and `retired/`, none used the singular key.

### `VarsActor.expand(String)` is removed

`VarsActor.expand` replaced each `${name}` in a text with the value of that variable. Nothing called it: the interpreter has not expanded `${...}` in arguments since 4.1.0.

**What 4.8 code must change:** Java that called it builds the text itself; a workflow reads the variable with an expression.

```java
// 4.8
String text = varsActor.expand("Hello ${user}");

// 5.0
String text = "Hello " + varsActor.wrapped().get("user");
```

```yaml
# 5.0, in a workflow argument
arguments: "jexl: 'Hello ' + actors.get('vars').get('user')"
# or, for a -P value
arguments: "jexl: 'Hello ' + state.getString('user')"
```

### `parallel-map.run` takes the items as an argument and answers the results

`ParallelMapActor.MapArgs` changed from `(subWorkflow, inputList, outputList, maxParallel, baseDir)` to `(subWorkflow, items, maxParallel, baseDir)`.

Up to 4.8.0, `parallel-map.run` read its items from a `list:` actor named by `inputList`, put each item into the sub-workflow's `str:input`, read the answer from `str:output`, and appended the answers to the `list:` actor named by `outputList`. Those actors are removed in 5.0.0.

Now:

- `items` is a JSON array, usually read from the caller's state with `jexl: state.select('xs')`.
- Each item is put in the sub-workflow's own state at `input`, and its answer is read from the sub-workflow's own state at `output`.
- When every item succeeded, the action is true and its result is the JSON array of the answers, in item order. The caller keeps it with `this.putJson {path: ys, value: "jexl: result"}`, which stores it as a list.
- When the sub-workflow failed for any item, the action is false with `<F> of <T> failed: <index> (<item>): <reason>; ... . parallel-map: mapped <T> item(s)` (`0f308f6`). The answers of the items that succeeded are not returned in that case.
- An item's run that ended with an exception gives the reason `error: <class>: <message> (in parallel-map item <index>)`.
- A fatal error from an item's run (a `VirtualMachineError` other than `StackOverflowError`) is rethrown out of the action, so the calling workflow records it as its last result and ends.

**What 4.8 YAML must change:**

```yaml
# 4.8: the caller
      - actor: parallel-map
        method: run
        arguments: {subWorkflow: one.yaml, inputList: "list:in", outputList: "list:out", maxParallel: 4}
# 4.8: one.yaml
      - actor: str:output
        method: set
        arguments: "jexl: 'processed: ' + actors.get('str:input').get()"

# 5.0: the caller
      - actor: parallel-map
        method: run
        arguments: {subWorkflow: one.yaml, items: "jexl: state.select('in')", maxParallel: 4}
      - actor: this
        method: putJson
        arguments: {path: out, value: "jexl: result"}
# 5.0: one.yaml
      - actor: this
        method: putJson
        arguments: {path: output, value: "jexl: 'processed: ' + state.getString('input')"}
```

### A list or object read from the state arrives at an action as JSON, not as text

An argument expression that answers a part of the state, such as `jexl: state.select('xs')`, reaches the action as the JSON array or object it is. Up to 4.8.0 it reached the action as its text (`"[\"a\",\"b\"]"`), and one text item of it (`state.select('xs').get(0)`) arrived with its quotes (`"\"a\""`).

**What 4.8 YAML must change:** nothing, unless an action parsed the text form itself. `.asText()` and `state.getString(...)` give text as before.

### A failed action is false, and `result` says why: `error: <class>: <message> (in <actor>.<method>)`

Commit `fbdd7e7`.

In Turing-workflow a failed action is `false`, and the YAML decides what to do with it. Up to 4.8.0 the reason did not reach the YAML intact:

- `InterpreterIIAR` answered a bare `Execution error`.
- A POOL action that threw returned `Action failed: <message>` without the exception's type (and `Action failed: null` without a message), and never set the last result.
- A DIRECT action, or a POOL action run inline, that threw escaped the run.
- `ActionDispatcher` dropped the exception's type.

Now:

- Every failed result made from an exception has one form, built only by `ActionResult.error(Throwable, String)`:

  ```
  error: <class of the root exception>: <message> (in <actor>.<method>)
  error: java.net.ConnectException: Connection refused (in net.connect)
  ```

  `ExecutionException`, `CompletionException` and `InvocationTargetException` are unwrapped, and the exception inside them is named. An exception without a message gives the class name alone.
- `Interpreter.action` catches an exception from every execution mode (POOL, DIRECT, POOL run inline). It records every failed result as the last result, including a failure that happens before the actor is called (a bad argument, an actor that is not found).
- The engine does not stop the workflow or rethrow. It tries the next candidate transition from the same state, as before.

The texts replaced include `Execution error`, `Interrupted`, `File not found: <path>`, `IO error: <path>`, `Action failed: …`, `Error in <action>: …`, `Failed to parse arguments for …`, `Subworkflow error: …`, `Sub-workflow call error: …`, `Apply error: …`, `Workflow not found: …`, `Interrupted at breakpoint`, `Interrupted during delay`, and the `loader.*`, `calc.eval`, `str.*`, `out.printf`, `putJson`/`appendJson`/`getJson`/`hasJson` and `parallel-map.run` errors. Every one now has the `error: …` form above. In an `apply` over child actors, each child's failure in the summary has that form too.

**What 4.8 YAML or code must change:** a transition, a test or a log check that compared one of the old texts must compare the new form. To tell an exception from an expected `false`, test the `error:` prefix. Since every action, `onlyIf` included, replaces the last result, keep the reason first:

```yaml
# 4.8: there was no stable text to test
# 5.0
  - states: ["0", "failed"]
    actions:
      - actor: this
        method: putJson
        arguments: {path: reason, value: "jexl: result"}
  - states: ["failed", "error"]
    actions:
      - actor: this
        method: onlyIf
        arguments: "jexl: state.getString('reason').startsWith('error:')"
```

The full sample is `src/main/resources/examples/branch-on-failure-reason.yaml`. An actor written for 4.8 that returns `new ActionResult(false, "…: " + e.getMessage())` from a `catch` can return `ActionResult.error(e, getName() + ".<action>")` to produce the same form.

### "No matching state transition" names the state and the last failure

Commit `fbdd7e7`.

Up to 4.8.0, when no transition matched, the run's result was `No matching state transition`, and it replaced the reason of the action that had failed. Now it is:

```
No matching state transition from '<state>'; last failed action: <that action's result>
```

The part after `;` appears only when an action failed while the engine looked for a transition.

**What 4.8 code must change:** a comparison with the exact text `No matching state transition` must become a prefix test (`startsWith("No matching state transition from ")`).

### A POOL action already on the pool's real thread runs inline

Commit `0949467`. Needs POJO-actor 5.0.0 (`ManagedThreadPool.ownsCurrentThread()`).

Up to 4.8.0, `this.call` ran the child workflow on the real thread of the calling POOL action. The child's POOL actions went through `supplyAsync(…, pool).get()` and waited for the same pool. Once every real thread of the pool was waiting this way (with one real thread, at the first call), a sub-workflow with POOL actions deadlocked.

Now `Interpreter.action` calls the actor directly when the pool returned by `system.getManagedThreadPool()` owns the current real thread. Otherwise it hands the action to the pool as before. A top-level POOL action called from a virtual thread or from another real thread still runs on the pool's real thread.

**What 4.8 YAML or code must change:** nothing.

### `requestStop()` wakes a run paused at a breakpoint

Commit `6b85979`.

Up to 4.8.0, `Interpreter.requestStop()` only set a flag. A run paused at a breakpoint waited on a lock that only `resume()` notified, so a stop sent while the run was paused was never seen, and `runUntilEnd()` waited forever. Now `requestStop()` sets the flag under that lock and notifies it. The paused run wakes, sees the stop, and returns `Stopped at breakpoint`.

**What 4.8 code must change:** code that called `resume()` after `requestStop()` to get a paused run to notice the stop can drop the `resume()`.

### `RunCLI` terminates the actor system when the run throws

Commit `10d0ba5`.

Up to 4.8.0, `RunCLI.execute()` called `system.terminateIIActors()`, `system.terminate()` and `System.exit()` only after `interpreter.runUntilEnd()` had returned. When the run threw (for example from an adapter whose `callByActionName` throws on a DIRECT action), these calls were skipped, and the pools' non-daemon real threads kept the JVM alive. A Kubernetes Job running the workflow never ended.

Now `java -jar turing-workflow-<version>-shaded.jar run …`:

- terminates the actor system in a `finally` block;
- reports a `Throwable` from the run on `System.err` as `Workflow failed: <throwable>`, with its stack trace;
- exits with status 1.

**What 4.8 code must change:** nothing. A script that detected the hang by a timeout now sees exit status 1.

### `RunCLI` registers the output multiplexer as `outputMultiplexer` only, not as `log`; `Interpreter.addToAccumulator` is removed

Branch `t253` (job t273, `c769d81`); the commit hash changes when it is rebased onto `main`.

Up to 4.8.0, `RunCLI` registered the output multiplexer under the name `log`, while the writers in the plugins looked it up as `outputMultiplexer`. Under `RunCLI` their output was dropped without a word. The multiplexer is now registered once, as `outputMultiplexer`; Java code names it with the constant `MultiplexerAccumulatorIIAR.ACTOR_NAME`. Nothing is registered as `log`.

`Interpreter.addToAccumulator(String, String)` and `addToAccumulator(String, String, String)` are removed. They were never actions, and nothing called them; a workflow that calls `this.addToAccumulator` gets an unknown action.

**What 4.8 YAML must change:**

```yaml
# 4.8
      - actor: log
        method: add
        arguments: {source: "deploy", type: "stdout", data: "done"}

# 5.0
      - actor: outputMultiplexer
        method: add
        arguments: {source: "deploy", type: "stdout", data: "done"}
```

The arguments stay the same. The README's old example called `log.info`, an action that never existed; it now shows `outputMultiplexer.add`.

The YAML under `~/works/workflow/` that use `actor: log` (for example `test-log-output.yaml`) need no change: they run on the editor's engine from before 4.1, which registers its own `LogActor` as `log`.

### Logging through the multiplexer never blocks or recurses

Commit `8d05f85`.

Up to 4.8.0, `MultiplexerAccumulatorIIAR.add` waited with `tell(…).get()`, so a log call blocked its caller whenever the multiplexer's message loop was busy. When the hand-over failed, its `catch` logged through JUL, which re-entered `MultiplexerLogHandler` and `add` again.

Now:

- `add` hands the entry over without waiting.
- The action's result message is `Enqueued` (it was `Added`).
- An entry whose hand-over fails, at once or later, is written to `System.err`, never through JUL. `MultiplexerLogHandler` also writes the record to `System.err` when `add` reports failure.

**What 4.8 code must change:** a check of the message `Added` from the multiplexer's `add` must accept `Enqueued`. The message no longer means the entry was stored: it means the entry was handed over.

### Documentation: arguments are written with `jexl:`, not `${...}` or `$(actor.method)`

Commits `d53c7fc`, `41fdbc3`. Documentation only; the engine's behaviour is unchanged.

The interpreter has not expanded `${...}` or `$(actor.method)` in action arguments since `4b1cb67`, which was released in 4.1.0. Until 4.8.0, `SKILL.md` and `reference/*.md` still taught both forms. Every example now uses `jexl:`:

| Written for the old documentation | Write instead |
|---|---|
| `${result}` | `jexl: result` |
| `${key}` (a `-P key=value` variable) | `jexl: state.getString('key')` |
| `${varName}` in a sub-workflow | `jexl: actors.get('vars').get('varName')` |
| `$(calc.get)` | `jexl: actors.get('calc').toString()` (text) or `jexl: actors.get('calc').get()` (number) |
| `$(str:input.get)` | `jexl: actors.get('str:input').get()` |
| `$(list:items.join)` | call the action `list:items.join` in an earlier action, then pass `jexl: result` |

**What 4.8 YAML must change:** a workflow that still contains `${...}` or `$(...)` in its arguments already received the literal text under 4.8, so it is already broken. Rewrite it with the forms above. The Turing Workflow Editor substitutes `${key}` placeholders in the YAML text before a run, and kustomize overlays substitute `${varName}` from their `vars`. Both work on the YAML text, not on arguments, and are unchanged.

### Action manifests: one file per class under `META-INF/turing-actions/`; the jar has a module name

Commit `7ce5bb8`.

- `TuringPluginDoclet` writes one file per class with `@Action` methods, `META-INF/turing-actions/<binary class name>.json`. `ActionManifest` reads a class's file through that class's own class loader when the class is asked about.
- The services-file index `META-INF/services/com.scivicslab.pojoactor.action.schema.ActionManifestSource`, the per-jar `META-INF/turing-plugin/<groupId>.<artifactId>.json` and the shared `META-INF/turing-plugin.json` are no longer written or read. Because the file names are class names, an uber-jar keeps every jar's manifests. Up to 4.8.0 it kept one shared `turing-plugin.json` and lost the other plugins' action descriptions.
- The jar declares `Automatic-Module-Name: com.scivicslab.turingworkflow`. From 4.3.0 to 4.8.0 `turing-workflow` could not be resolved as a module at all; 4.2.0 resolved under the name derived from its file name, `turing.workflow`.

**Breaking:**

- The doclet's `-turingOutputDir` and `-turingManifestResource` options are removed. Pass only `-turingClassesDir ${project.build.outputDirectory}`.
- A project that shipped its own manifest index or `turing-plugin.json` must rebuild with the new doclet; the old files are ignored.
- `ActionManifest`'s `DEFAULT_RESOURCE`, `INDEX_RESOURCE`, `addIndexed`, `addFrom`, `size()` and its `(ClassLoader, String)` constructor are removed. Use `new ActionManifest()` or `new ActionManifest(ClassLoader)`, and `docFor`.

**What 4.8 code must change:**

The doclet was configured in one of two ways in 4.8. Both become the same 5.0 configuration.

```xml
<!-- 4.8, one shared file: META-INF/turing-plugin.json (the plugins used this until cf82849) -->
<additionalOption>-turingOutputDir</additionalOption>
<additionalOption>${project.build.outputDirectory}/META-INF</additionalOption>

<!-- 4.8, one file per jar: META-INF/turing-plugin/<groupId>.<artifactId>.json
     (Turing-workflow, quarkus-chat-ui and chat-ui-with-audit-trail use this) -->
<additionalOption>-turingClassesDir</additionalOption>
<additionalOption>${project.build.outputDirectory}</additionalOption>
<additionalOption>-turingManifestResource</additionalOption>
<additionalOption>META-INF/turing-plugin/${project.groupId}.${project.artifactId}.json</additionalOption>

<!-- 5.0 -->
<additionalOption>-turingClassesDir</additionalOption>
<additionalOption>${project.build.outputDirectory}</additionalOption>
```

A project in the per-jar form only deletes its two `-turingManifestResource` lines. A project in the shared-file form replaces its `-turingOutputDir` lines with `-turingClassesDir`.

Code that named a manifest resource, or read manifests from a loader by hand, no longer compiles:

```java
// 4.8
ActionManifest manifest = new ActionManifest(loader, "META-INF/my-actions.json");
manifest.addIndexed(pluginLoader);
manifest.addFrom(pluginLoader, "META-INF/turing-plugin/com.example.my-plugin.json");
int known = manifest.size();

// 5.0
ActionManifest manifest = new ActionManifest(loader);
ActionDoc doc = manifest.docFor(MyActor.class, "run");
```

A jar loaded at run time needs no `addFrom` any more: `docFor(Class, …)` reads the class's manifest through that class's own class loader.

`new ActionManifest(loader)` still compiles, but the loader means something narrower. In 4.8 it was the loader every manifest was read through. In 5.0 only `docFor(String className, …)` uses it, to find the class by name; `docFor(Class, …)` ignores it.

### Interpreter security limits

Branch `t250` (job t274); the commit hash changes when it is rebased onto `main`.

- **Sub-workflow depth.** `this.call` and `runWorkflow` count how deep they are nested. One level past `Interpreter.DEFAULT_MAX_CALL_DEPTH` (64), the call is false with `sub-workflow depth limit reached: … would run at depth N, the limit is 64 (system property turingworkflow.maxCallDepth)`. The system property `turingworkflow.maxCallDepth` raises or lowers the limit. A workflow that calls itself now stops there instead of running out of stack.
- **`putJson` list index.** A path naming a list index above `IIActorRef.MAX_PUT_JSON_INDEX` (100000), anywhere in the path (`items[N]`, `rows[N].name`), is refused with `putJson: index N in path P is above the limit of 100000`, and nothing is stored. Up to 4.8 the state filled the list up to the index, so a large one ran the JVM out of memory. `appendJson` names no index and is not limited.
- **`jexl:` failure text.** An argument expression that cannot be evaluated is reported as `argument expression failed: <expression> -- <class of the innermost cause> at line L, column C`, and the cause's message is left out. Up to 4.8 the message was included, and a parse error quoted the text it was parsing (for example the contents of a file). A message the interpreter itself wrote, such as the reason an actor was removed in 5.0.0, is still shown.
- **Path loads stay under the base directory.** The `readYaml` action and `runWorkflow` read a file only under the interpreter's base directory (`setWorkflowBaseDir`, or RunCLI's `-d`). A path that climbs out with `..`, an absolute path elsewhere, and a link that points elsewhere are refused with `outside the base directory: …`. A relative path given to `readYaml` is now relative to the base directory, not to the process's working directory. The check is `PathUnderBase.resolve(baseDir, path)`. `parallel-map` runs its sub-workflows under the base directory of the interpreter that calls it, and an explicit `baseDir` argument must lie under that one.
- **No base directory set means no restriction.** An interpreter that was never given a base directory reads paths as before. RunCLI (`-d`) and quarkus-chat-ui set one. chat-ui-with-audit-trail and Turing-workflow-editor do not, so their workflows can still read any path the process can.
- **The loader's log.** `DynamicActorLoaderActor` logs each action and its arguments at FINE instead of INFO. In its log lines, the user and password of a URL (`https://user:***@host/`) and the value of a parameter named like a secret (`password`, `token`, `secret`, `key`, …) are replaced with `***`.

**What 4.8 code must change:** a workflow that loads a file outside its base directory moves the file under it, or is run with a base directory that contains it. A `putJson` above index 100000 uses `appendJson`, or a map instead of a list. Code that matched the old `jexl:` failure text reads the class name instead.
