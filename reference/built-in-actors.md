# Built-in Actors — 詳細リファレンス

組み込みアクターの全メソッドと使用例。SKILL.md から参照されるドキュメント。

---

## 3. Built-in Actors

The following actors are always available without any plugin loading.

### 3.1 `loader` — Dynamic plugin loader

Loads external JAR files and creates actors from their classes at runtime.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `loadJar` | `"path/to/plugin.jar"` or `"groupId:artifactId:version"` | Loads a JAR; Maven coordinates resolve from `~/.m2/repository/` |
| `createChild` | `["parentName", "actorName", "com.example.ActorClass"]` | Creates an actor under a parent; class must be `IIActorRef` subclass |
| `listLoadedJars` | `""` | Lists all loaded JAR paths |

**Two-step pattern (recommended):**
```yaml
- states: ["0", "1"]
  label: load-plugin
  actions:
    - actor: loader
      method: loadJar
      arguments: "/data/jars/my-plugin-1.0.0.jar"

- states: ["1", "2"]
  label: create-actors
  actions:
    - actor: loader
      method: createChild
      arguments: ["ROOT", "myActor", "com.example.MyActorIIAR"]
```

### 3.2 `log` — Output accumulator

Accumulates structured log entries. By default, entries are printed to stdout.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `add` | `{"source": "name", "type": "stdout\|stderr", "data": "message"}` | Adds a log entry |
| `getSummary` | `""` | Returns formatted summary of all entries |
| `getCount` | `""` | Returns the number of accumulated entries |
| `clear` | `""` | Clears all entries |

### 3.3 `vars` — Variable store

Holds key-value pairs. Pre-populated with `-P` options from the CLI.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `get` | `"varName"` or `["varName"]` | Returns the variable value |
| `set` | `["varName", "value"]` | Sets a variable |
| `list` | `""` | Lists all variable names |

### 3.4 `interpreter` — Workflow engine self-reference

| Method | Arguments | Description |
|--------|-----------|-------------|
| `putJson` | `{"path": "key", "value": "val"}` | Stores a value in JSON state (read back with `jexl: state.getString('key')`) |
| `getJson` | `"key"` or `["key"]` | Gets a value from JSON state |
| `hasJson` | `"key"` | Returns `"true"` or `"false"` |
| `clearJson` | `""` | Clears JSON state |
| `printJson` | `""` | Prints JSON state to stdout |
| `onlyIf` | `"jexl: <condition>"` | Succeeds when the condition holds. Fails when it does not, or when it was never a yes-or-no answer, so the engine falls through to the next candidate transition |
| `appendJson` | `{"path": "key.list", "value": <any>}` | Adds one value to the end of the list at that path |
| `sleep` | `"1000"` | Sleeps N milliseconds |
| `print` | `"text"` | Prints text to stdout |
| `doNothing` | `""` | No-op (always succeeds) |

### 3.5 JSON State API (available on ALL actors)

Every actor inherits these actions from `IIActorRef`:

| Method | Arguments | Description |
|--------|-----------|-------------|
| `putJson` | `{"path": "key.nested", "value": <any>}` | Stores value in actor's JSON state. An object or array argument is stored as structure, so `key.nested.field` reaches into it |
| `appendJson` | `{"path": "key.list", "value": <any>}` | Adds one value to the end of the list at that path. A path holding nothing becomes a list of one; a path holding anything that is not a list is refused |
| `getJson` | `"key.nested"` | Reads from actor's JSON state; the value becomes the last result (`jexl: result`) |
| `hasJson` | `"key"` | Checks existence; returns `"true"`/`"false"` |
| `clearJson` | `""` | Clears actor's JSON state |
| `printJson` | `""` | Prints actor's JSON state to stdout |

### 3.6 `calc`, `list`, `str` — removed in 5.0.0

The value actors `calc` / `calc:name`, `list` / `list:name` and `str` / `str:name` were removed in
5.0.0. They came from the time before `jexl:` expressions, when an argument could not compute
anything. A workflow keeps its numbers, text and lists in its own JSON state, writes them with
`putJson` and `appendJson`, and reads them with `jexl: state...` (SKILL.md 3.2,
`ActorsAsVariables_260914_oo01`).

Nothing is made under these names any more. An action that names one of them is false, and the
next transition reads this reason in `jexl: result`:

```
error: the built-in actor 'calc:i' was removed in 5.0.0; keep the value in the workflow's own state instead: write it with this.putJson or this.appendJson, read it with jexl: state... (in calc:i.inc)
```

| Written for 4.x | Write instead |
|---|---|
| `calc:i.set "0"` | `this.putJson {path: i, value: 0}` |
| `calc:i.inc` | `this.putJson {path: i, value: "jexl: state.getInt('i', 0) + 1"}` |
| `calc:i.add "5"` | `this.putJson {path: i, value: "jexl: state.getInt('i', 0) + 5"}` |
| `jexl: actors.get('calc:i').get()` | `jexl: state.getInt('i', 0)` (or `state.getDouble('x', 0)` for a fraction) |
| `list:items.add "a"` | `this.appendJson {path: items, value: "a"}` |
| `list:items.size` | `jexl: state.select('items').size()` |
| `list:items.get {index: n}` | `jexl: state.select('items').get(n).asText()` |
| `str:msg.set "..."` | `this.putJson {path: msg, value: "..."}` |
| `jexl: actors.get('str:msg').get()` | `jexl: state.getString('msg')` |
| `str:body.escapeJsonStored`, then pasting it into a JSON text | pass a YAML map whose values are `jexl:` expressions; each value is built as JSON by the engine |

### 3.7 `out` — Print output

`out` is the supported built-in way to print. It is made on first use, with no warning.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `print` | `"message"` | Print to stdout with newline |
| `error` | `"message"` | Print to stderr with newline |
| `printf` | `["format", arg1, arg2, ...]` | Java `String.format`-style formatted print |

```yaml
- actor: out
  method: print
  arguments: "jexl: 'Done. count=' + state.getInt('count', 0)"
- actor: out
  method: printf
  arguments: ["Result: %s (%.2f sec)", "jexl: result", "jexl: state.getDouble('elapsed', 0)"]
```

### 3.8 `this` — Sub-workflow invocation

Calls another workflow YAML file as a child interpreter. The child shares the same `IIActorSystem`
as the parent — including the `vars` actor — so variables set with `vars.set` before the call are
readable inside the sub-workflow as `jexl: actors.get('vars').get('varName')`.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `call` | `["sub.yaml"]` | Load and run `sub.yaml` (resolved relative to `-d` base directory); blocks until the sub-workflow reaches `"end"` |

**Parameter passing pattern:**
```yaml
# Parent workflow — set vars before calling the sub-workflow
- states: ["0", "1"]
  label: setup
  actions:
    - actor: vars
      method: set
      arguments: ["repo", "owner/my-repo"]
    - actor: vars
      method: set
      arguments: ["dir", "/home/devteam/works/my-repo"]
    - actor: vars
      method: set
      arguments: ["task", "Build and deploy the project."]

- states: ["1", "2"]
  label: publish
  actions:
    - actor: this
      method: call
      arguments: ["github-publish.yaml"]
```

Inside `github-publish.yaml`, `repo`, `dir` and `task` are readable as
`jexl: actors.get('vars').get('repo')` and so on, because the child interpreter shares the
parent's `vars` actor.

**Notes:**
- The sub-workflow file path is resolved relative to the `-d` base directory passed on the CLI.
- Actors created inside the sub-workflow (e.g., `llm`, `promptBuilder`) persist in the shared
  `IIActorSystem` after the call returns. On the second call to the same sub-workflow,
  `loader.createChild` for the same actor name may be a no-op or error — design sub-workflows
  to be idempotent when called multiple times, or use uniquely named actors.
- The last result (`jexl: result`) after `this.call` holds the final output of the sub-workflow.

### 3.9 `parallel-map` — Run a sub-workflow over every item, in parallel

Made on first use as `parallel-map`. Where `this.call` runs one sub-workflow, `parallel-map.run` runs a
sub-workflow once per item, several at a time, and answers what each run left.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `run` | `{subWorkflow, items, maxParallel?, baseDir?}` | Runs `subWorkflow` once per element of `items` (a JSON array); at most `maxParallel` at once (default 6) |

Each item runs in its own isolated actor system, which shares nothing with the caller: the
sub-workflow loads any plugin it needs itself. The item is put in the sub-workflow's own state at
`input`, and the sub-workflow leaves its answer in its own state at `output`.

When every item succeeded, the action is true and its result is the JSON array of the answers, in
item order. The caller stores it with `this.putJson`, and the array is kept as a list:

```yaml
# Caller: map the list at xs, keep the answers at ys
- states: ["1", "2"]
  actions:
    - actor: parallel-map
      method: run
      arguments:
        subWorkflow: "summarize-one.yaml"
        items: "jexl: state.select('xs')"
        maxParallel: 4
- states: ["2", "3"]
  actions:
    - actor: this
      method: putJson
      arguments: {path: ys, value: "jexl: result"}
```

```yaml
# summarize-one.yaml: read the item at input, leave the answer at output
- states: ["0", "end"]
  actions:
    - actor: this
      method: putJson
      arguments: {path: output, value: "jexl: 'processed: ' + state.getString('input')"}
```

When the sub-workflow failed for any item, the action is false and names each failed item and why:

```
1 of 3 failed: 1 (beta): error: sub-workflow failed: No matching state transition from '0' ... . parallel-map: mapped 3 item(s)
```

A fatal error from an item's run (a `VirtualMachineError` other than `StackOverflowError`) is not
one item's failure: it is rethrown, so the calling workflow records it as its last result and ends.
