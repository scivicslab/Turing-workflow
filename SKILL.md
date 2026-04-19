# Turing Workflow — Complete Reference Skill

Turing Workflow is a YAML-based state-machine workflow engine built on the POJO-actor framework.
A workflow consists of **actors** and **transitions**. Actors hold state and expose named actions.
Transitions fire when the engine is in a matching state; all actions in a transition must succeed
for the state to advance. Failed actions cause the engine to look for an alternative transition
from the same source state.

---

## 1. Workflow YAML Structure

```yaml
name: my-workflow          # human-readable name
description: |             # optional multi-line description
  What this workflow does.

params:                    # optional: parameter metadata for the workflow editor UI
  repo:
    description: "Target GitHub repository (e.g. owner/repo-name)"
  dir:
    description: "Absolute path to the local project directory"
  agent:
    description: "Agent name to call"
    default: "claude"

steps:
  - states: ["0", "1"]     # [currentState, nextState]
    label: my-step         # optional stable id (for overlays/patches)
    note: "what this does" # optional documentation-only comment
    delay: 500             # optional: milliseconds to wait before executing
    actions:
      - actor: someActor
        method: someMethod
        arguments: "value"

  - states: ["1", "2"]
    actions:
      - actor: anotherActor
        method: doThing
        arguments: ["arg1", "arg2"]
```

**`params` section** — optional metadata consumed by the Turing Workflow Editor Run panel.
When present, the editor shows each parameter's description as a placeholder and pre-fills
its `default` value in the input form. Parameters without a `default` require manual input.
The `params` section has no effect on CLI invocation (`-P key=value` works regardless).

```yaml
params:
  myParam:
    description: "What this parameter means"   # shown as placeholder in the UI
    default: "someValue"                       # pre-filled in the UI; optional
```

**Key rules:**
- `states` has exactly 2 elements: `[from, to]`.
- The initial state is always `"0"`.
- The terminal state is always `"end"`.
- Multiple transitions can share the same `from` state — the engine tries them in order.
  The **first transition whose every action succeeds** wins and advances to `nextState`.
  Transitions whose actions fail are skipped (the engine moves to the next candidate).
- The `!end` wildcard in `from` matches any state that is not `"end"`, enabling catch-all fallback transitions.
- `arguments` may be a plain string, a JSON array, or a YAML map/object.

### Variable substitution

Variables passed via `-P key=value` on the CLI (or set inside the workflow) are available
anywhere in `arguments` as `${varName}`. The interpreter also stores the `result` of the
most recently executed action as `${result}`.

```yaml
arguments: "${ocr.file}"      # expands to the value of variable ocr.file
arguments: "${result}"        # expands to the result of the previous action
```

`${result}` is evaluated **before** the current action executes, so it always holds the
result of the **previous** action — even across step boundaries.

### `$(actor.method)` — Inline method call embedding

Embeds the return value of another actor's method directly into an argument string.
The call happens at argument-evaluation time (before the current action runs).

```yaml
arguments: "$(calc.get)"               # inserts current value of calc
arguments: "$(calc:i.get)"             # named instance
arguments: "value is $(calc.get) now"  # mixed with literal text
arguments: "$(list:items.get)"         # reads index 0 of list:items (default)
```

### JEXL expressions in `states`

Both elements of `states` may be JEXL expressions prefixed with `jexl:`.
Available variables: `state` / `s` (current state string), `n` (state parsed as number, null if non-numeric).

```yaml
states: ["jexl:n < 10", "jexl:n + 1"]   # loop: state increments each iteration
states: ["jexl:n >= 10", "end"]          # exit when counter reaches 10
states: ["jexl:state == 'error'", "end"] # string comparison
states: [">=10", "end"]                  # shorthand numeric comparison (no jexl: prefix)
```

---

## 2. CLI Invocation

```bash
java -jar turing-workflow-<version>.jar run \
  -w <workflow.yaml>           # path to workflow YAML (required)
  -d <base-dir>                # base directory for resolving relative paths (default: .)
  -m <maxIterations>           # max loop iterations (default: 10000)
  -o <overlay-dir>             # kustomize overlay directory (optional)
  -P key=value                 # define a variable; can be repeated
```

**Example:**
```bash
java -jar turing-workflow-3.0.1.jar run \
  -w /data/workflow/kana-kanji-pairs.yaml \
  -P ocr.file=/data/ocr/book001.tsv \
  -P pairs.file=/data/output/pairs.tsv \
  -P vllm.url=http://192.168.5.16:8000/v1/chat/completions \
  -P vllm.model=Qwen3.5-35B-A3B
```

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
| `putJson` | `{"path": "key", "value": "val"}` | Stores a value in JSON state (accessible via `${key}`) |
| `getJson` | `"key"` or `["key"]` | Gets a value from JSON state |
| `hasJson` | `"key"` | Returns `"true"` or `"false"` |
| `clearJson` | `""` | Clears JSON state |
| `printJson` | `""` | Prints JSON state to stdout |
| `sleep` | `"1000"` | Sleeps N milliseconds |
| `print` | `"text"` | Prints text to stdout |
| `doNothing` | `""` | No-op (always succeeds) |

### 3.5 JSON State API (available on ALL actors)

Every actor inherits these actions from `IIActorRef`:

| Method | Arguments | Description |
|--------|-----------|-------------|
| `putJson` | `{"path": "key.nested", "value": <any>}` | Stores value in actor's JSON state |
| `getJson` | `"key.nested"` | Reads from actor's JSON state; result goes to `${result}` |
| `hasJson` | `"key"` | Checks existence; returns `"true"`/`"false"` |
| `clearJson` | `""` | Clears actor's JSON state |
| `printJson` | `""` | Prints actor's JSON state to stdout |

### 3.6 `calc` / `calc:name` — Numeric variable

Auto-created on first use. Named instances (`calc:x`, `calc:count`) are independent.
Initial value is `0`. Returns the new value as a string after each operation.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `set` | `"3.14"` | Set value |
| `get` | `""` | Read value |
| `inc` | `""` | Increment by 1 |
| `dec` | `""` | Decrement by 1 |
| `add` | `"5"` | Add to value |
| `sub` | `"5"` | Subtract from value |
| `mul` | `"2"` | Multiply value |
| `div` | `"2"` | Divide value |
| `mod` | `"3"` | Modulo |
| `reset` | `""` | Reset to 0 |
| `eval` | `"v * 2 + 1"` | Evaluate JEXL expression; `v` = current value |

```yaml
- actor: calc:i
  method: set
  arguments: "0"
- actor: calc:i
  method: inc
```

### 3.7 `list` / `list:name` — String list

Auto-created on first use. Named instances (`list:files`, `list:errors`) are independent.
Wraps `ArrayList<String>`.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `add` | `"value"` | Append element |
| `get` | `"0"` (index; defaults to 0 if blank) | Get element by index |
| `set` | `[index, "value"]` | Replace element at index |
| `remove` | `"0"` (index) | Remove element by index |
| `size` | `""` | List length |
| `isEmpty` | `""` | True if empty |
| `clear` | `""` | Remove all elements |
| `contains` | `"value"` | True if element exists |
| `indexOf` | `"value"` | Index of element (-1 if not found) |
| `join` | `","` (separator; defaults to `", "` if blank) | Join elements with separator |

```yaml
- actor: list:items
  method: add
  arguments: "hello"
- actor: out
  method: print
  arguments: "$(list:items.join)"   # prints: hello
```

### 3.8 `str` / `str:name` — String variable

Auto-created on first use. Named instances (`str:title`, `str:body`) are independent.
Stores a single mutable string (initial value: `""`).

| Method | Arguments | Description |
|--------|-----------|-------------|
| `set` | `"value"` | Set stored string |
| `get` | `""` | Read stored string |
| `clear` | `""` | Reset to empty string |
| `append` | `"text"` | Append text to stored string |
| `length` | `""` | Character count |
| `trim` | `""` | Remove leading/trailing whitespace (in-place) |
| `toUpperCase` | `""` | Convert to uppercase (in-place) |
| `toLowerCase` | `""` | Convert to lowercase (in-place) |
| `contains` | `"text"` | True if stored string contains argument |
| `startsWith` | `"prefix"` | True if stored string starts with prefix |
| `endsWith` | `"suffix"` | True if stored string ends with suffix |
| `replace` | `["target", "replacement"]` | Replace all occurrences (in-place) |
| `substring` | `[start]` or `[start, end]` | Extract substring |
| `isEmpty` | `""` | True if stored string is empty |
| `escapeJson` | `"raw text"` | JSON-escape the **argument** (does not modify stored value) |
| `escapeJsonStored` | `""` | JSON-escape the **stored string** in-place |

**Typical use — safe JSON embedding:**
```yaml
# Store LLM response then escape it before embedding in JSON argument
- actor: str:body
  method: set
  arguments: "${result}"
- actor: str:body
  method: escapeJsonStored
- actor: llm
  method: callAgent
  arguments: '{"agent": "reviewer", "prompt": "$(str:body.get)"}'
```

### 3.9 `out` — Print output

Auto-created on first use.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `print` | `"message"` | Print to stdout with newline |
| `error` | `"message"` | Print to stderr with newline |
| `printf` | `["format", arg1, arg2, ...]` | Java `String.format`-style formatted print |

```yaml
- actor: out
  method: print
  arguments: "Done. count=$(calc.get)"
- actor: out
  method: printf
  arguments: ["Result: %s (%.2f sec)", "${result}", "$(calc:elapsed.get)"]
```

---

## 4. Execution Model

1. Engine starts in state `"0"`.
2. It scans the transition list from top to bottom for transitions whose `from` matches the current state.
3. For each candidate transition, it executes all `actions` in order.
   - Each action calls `actor.method(arguments)` which returns `ActionResult(success, message)`.
   - If all actions return success=true, the engine moves to `to` state. Loop repeats.
   - If any action returns success=false, the engine skips to the next candidate transition (from the same `from` state).
4. If no candidate transition succeeds, execution fails.
5. When state becomes `"end"`, execution completes successfully.
6. The `${result}` variable is automatically updated after every action with the `message` from `ActionResult`.

---

## 5. Common Patterns

### 5.1 Linear pipeline

```yaml
steps:
  - states: ["0", "1"]
    actions:
      - actor: loader
        method: loadJar
        arguments: "/path/to/plugin.jar"

  - states: ["1", "2"]
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "worker", "com.example.WorkerIIAR"]

  - states: ["2", "3"]
    actions:
      - actor: worker
        method: process
        arguments: "${input.file}"

  - states: ["3", "end"]
    actions:
      - actor: log
        method: add
        arguments: {"source": "pipeline", "type": "stdout", "data": "Done"}
```

### 5.2 Conditional branching (two alternatives from same state)

```yaml
# Try step1 — if it succeeds, go to state 5
- states: ["4", "5"]
  label: step1-ok
  actions:
    - actor: worker
      method: tryProcess
      arguments: "${result}"

# If step1 failed, skip and loop back to state 3
- states: ["4", "3"]
  label: step1-failed-skip
  actions:
    - actor: log
      method: add
      arguments: {"source": "worker", "type": "stderr", "data": "Step 1 failed, skipping"}
```

### 5.3 Loop with sentinel (exhaustion-based)

The actor returns `ActionResult(false, ...)` when exhausted. The engine then falls through to
the exit transition from the same state.

```yaml
# Try to get next item; success → process it, failure → exit loop
- states: ["loop", "process"]
  label: get-next
  actions:
    - actor: promptBuilder
      method: getNextWarning    # returns false when all warnings consumed

# No more items — exit
- states: ["loop", "end"]
  label: loop-done
  actions:
    - actor: log
      method: add
      arguments: {"source": "workflow", "type": "stdout", "data": "All done."}

# Process item (${result} = warning text from getNextWarning)
- states: ["process", "loop"]
  label: process-item
  actions:
    - actor: llm
      method: prompt
      arguments: "Check: ${result}"
```

### 5.4 Counter loop with `calc`

Use the built-in `calc` actor as a counter. Exit by checking size.

```yaml
- states: ["init", "loop"]
  label: init-counter
  actions:
    - actor: calc:i
      method: set
      arguments: "0"

# Loop body: process item at index i
- states: ["loop", "process"]
  label: get-item
  actions:
    - actor: myActor
      method: getItem
      arguments: "$(calc:i.get)"

# Exit when getItem fails (index out of range)
- states: ["loop", "end"]
  label: loop-done
  actions:
    - actor: out
      method: print
      arguments: "Done"

- states: ["process", "loop"]
  label: next
  actions:
    - actor: calc:i
      method: inc
```

### 5.5 JEXL state-counter loop

When the state itself can serve as the counter. Simplest pattern for fixed-count loops.

```yaml
# Loop body: state n < 5 → execute and advance state to n+1
- states: ["jexl:n < 5", "jexl:n + 1"]
  label: loop-body
  actions:
    - actor: out
      method: print
      arguments: "Iteration $(calc:i.get)"

# Exit: state n >= 5 → end
- states: ["jexl:n >= 5", "end"]
  label: loop-exit
  actions:
    - actor: out
      method: print
      arguments: "Done"
```

**Note:** When using JEXL states, the initial state must be a number (e.g., `"0"`) so `n` is parseable.

### 5.6 Catch-all error handler

```yaml
# Placed LAST — fires on any state that is not "end"
- states: ["!end", "end"]
  label: catch-all
  actions:
    - actor: pairs
      method: closeOutput
      arguments: ""
    - actor: log
      method: add
      arguments: {"source": "workflow", "type": "stderr", "data": "Workflow ended unexpectedly"}
```

### 5.7 Storing intermediate results between steps

```yaml
- states: ["5", "6"]
  label: store-result
  actions:
    - actor: vllm
      method: segment
      arguments: "${result}"
    - actor: interpreter
      method: putJson
      arguments: {path: segmented, value: "${result}"}

- states: ["8", "3"]
  label: use-stored
  actions:
    - actor: vllm
      method: toHiragana
      arguments: "${segmented}"
```

### 5.8 Safe JSON embedding with `str`

When `${result}` may contain newlines or quotes (LLM output), escape it before embedding in JSON.

```yaml
- states: ["5", "6"]
  label: escape-and-send
  actions:
    - actor: str:body
      method: set
      arguments: "${result}"
    - actor: str:body
      method: escapeJsonStored
    - actor: llm
      method: callAgent
      arguments: '{"agent": "reviewer", "prompt": "$(str:body.get)"}'
```

---

## 6. Plugin Reference

Plugins are loaded at runtime via the `loader` actor. They must be installed in the local Maven
repository (`~/.m2/repository/`) before use.

### Installing a plugin

```bash
# Clone the plugin repository and install to local Maven repo
git clone https://github.com/scivicslab/Turing-workflow-plugins.git
cd Turing-workflow-plugins
rm -rf target
mvn install
```

Or install a specific plugin only:
```bash
cd plugin-llm
rm -rf target
mvn install
```

After installation, the JAR can be referenced in workflows by Maven coordinate:
```yaml
- actor: loader
  method: loadJar
  arguments: "com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0"
```

Or by absolute path:
```yaml
- actor: loader
  method: loadJar
  arguments: "/data/jars/plugin-llm-1.0.0.jar"
```

---

### 6.1 `plugin-llm` — LLM via MCP

**Artifact:** `com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0`
**Class:** `com.scivicslab.turingworkflow.plugins.llm.LlmActor`
**Actor name convention:** `llm`

Calls LLM services via MCP (Model Context Protocol) Streamable HTTP transport.
Default target: `http://localhost:8090/mcp`.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `setUrl` | `"http://host:port/mcp"` | Set MCP server URL |
| `prompt` | `"prompt text"` | Send prompt; returns LLM response |
| `callAgent` | `["agentName", "promptText"]` or `{"agent":"name","prompt":"text","caller":"id"}` | Call named agent via MCP Gateway; blocks up to 5 minutes |
| `status` | `""` | Query service status |
| `listTools` | `""` | List available MCP tools |

```yaml
- states: ["0", "1"]
  actions:
    - actor: loader
      method: loadJar
      arguments: "com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0"

- states: ["1", "2"]
  actions:
    - actor: loader
      method: createChild
      arguments: ["ROOT", "llm", "com.scivicslab.turingworkflow.plugins.llm.LlmActor"]

- states: ["2", "3"]
  actions:
    - actor: llm
      method: setUrl
      arguments: "${llm.url}"

- states: ["3", "4"]
  actions:
    - actor: llm
      method: prompt
      arguments: "Summarize: ${result}"
```

---

### 6.2 `plugin-prompt-builder` — Prompt assembler

**Artifact:** `com.scivicslab.turingworkflow.plugins:plugin-prompt-builder:1.0.0`
**Class:** `com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor`
**Actor name convention:** `promptBuilder`

Assembles structured prompts from constraints (warnings), background context, and a message.
Output section headers: `[Constraints]`, `[Context]`, `[Message]`.
Sections with no entries are omitted. `build` fails if `addMessage` was not called.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `clear` | `""` | Clear all content and reset cursors |
| `addWarning` | `"constraint text"` | Add a constraint/warning section |
| `addContext` | `"background text"` | Add a background context section |
| `addMessage` | `"main message"` | Set the main message (required for `build`) |
| `build` | `""` | Build complete prompt string |
| `getWarningCount` | `""` | Number of warnings |
| `getWarning` | `"0"` (index) | Get warning at index; fails if out of range |
| `getNextWarning` | `""` | Cursor-based: get next warning; returns false when exhausted |
| `getContextCount` | `""` | Number of contexts |
| `getContext` | `"0"` (index) | Get context at index |
| `getNextContext` | `""` | Cursor-based: get next context; returns false when exhausted |
| `resetCursor` | `""` | Reset warning and context cursors to 0 |
| `getMessage` | `""` | Get the message text |

**Verification loop pattern:**
```yaml
# Init counter before loop
- states: ["7", "verify-loop"]
  label: init-verify-counter
  actions:
    - actor: calc
      method: set
      arguments: "0"

# Try to get warning at current index; success → verify, failure → done
- states: ["verify-loop", "verify-check"]
  label: get-next-warning
  actions:
    - actor: promptBuilder
      method: getWarning
      arguments: "$(calc.get)"

- states: ["verify-loop", "end"]
  label: verify-done
  actions:
    - actor: out
      method: print
      arguments: "Verification complete."

# Verify the warning and increment counter
- states: ["verify-check", "verify-next"]
  label: verify-warning
  actions:
    - actor: llm
      method: callAgent
      arguments: '{"agent": "${agent}", "prompt": "「${result}」は守られていましたか？OK/NG（理由付き）"}'

- states: ["verify-next", "verify-loop"]
  label: increment-counter
  actions:
    - actor: calc
      method: inc
```

---

### 6.3 `plugin-ssh` — SSH command execution

**Artifact:** `com.scivicslab.turingworkflow.plugins:plugin-ssh:1.0.0`
**Class:** `com.scivicslab.turingworkflow.plugins.ssh.NodeActor`
**Actor name convention:** `node-<hostname>`

Executes commands on remote nodes via SSH (or locally). Used with `plugin-inventory`
which creates `NodeActor` instances for each node in an inventory group.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `executeCommand` | `["command"]` | Run command; stream output to accumulator |
| `executeCommandQuiet` | `["command"]` | Run command without streaming; return exitCode/stdout/stderr |
| `executeSudoCommand` | `["command"]` | Run sudo command (requires `SUDO_PASSWORD` env var) |
| `executeSudoCommandQuiet` | `["command"]` | Run sudo command without streaming |
| `runWorkflow` | `["path/to/workflow.yaml"]` or `["path", maxIter]` | Load and run a sub-workflow |
| `sleep` | `["milliseconds"]` | Pause execution |
| `print` | `["text"]` | Print to stdout |
| `doNothing` | `["optional message"]` | No-op |
| `printJson` | `["path"]` | Print JSON state at path |
| `printYaml` | `["path"]` | Print JSON state at path as YAML |

---

### 6.4 `plugin-inventory` — Multi-node orchestration

**Artifact:** `com.scivicslab.turingworkflow.plugins:plugin-inventory:1.0.0`
**Class:** `com.scivicslab.turingworkflow.plugins.inventory.NodeGroupActor`
**Actor name convention:** `nodeGroup`

Manages a group of nodes loaded from an inventory file. Creates child `NodeActor` instances
and dispatches actions to them in parallel using wildcard patterns.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `hasInventory` | `""` | True if inventory is loaded |
| `createNodeActors` | `["group-name"]` | Create child NodeActors for all nodes in group |
| `apply` | `{"actor":"node-*","method":"executeCommand","arguments":["cmd"]}` | Apply action to matching child actors in parallel |
| `executeCommandOnAllNodes` | `["command"]` | Run command on all child node actors |
| `runWorkflow` | `["path/to/workflow.yaml"]` | Load and run workflow |
| `getAccumulatorSummary` | `""` | Get collected results from accumulator |
| `printSessionSummary` | `""` | Print verification results summary (OK/WARN/ERROR counts) |
| `getSessionId` | `""` | Get current session ID |
| `doNothing` | `""` | No-op |
| `printJson` | `["path"]` | Print JSON state at path |
| `printYaml` | `["path"]` | Print JSON state at path as YAML |

---

### 6.5 `plugin-report` — Report generation

**Artifact:** `com.scivicslab.turingworkflow.plugins:plugin-report:1.0.0`
**Class:** `com.scivicslab.turingworkflow.plugins.report.ReportBuilderActor`
**Actor name convention:** `reportBuilder`

Assembles workflow execution reports from multiple sections and sends to accumulator.

| Method | Arguments | Description |
|--------|-----------|-------------|
| `addWorkflowInfo` | `""` | Add workflow name/description section |
| `addJsonStateSection` | `{"actor":"actorName","path":"optional.path"}` | Add actor JSON state as YAML section |
| `report` | `""` | Build and send complete report to `outputMultiplexer` |

---

## 7. Writing a Plugin Actor

A plugin actor is a JAR containing a class that extends `IIActorRef`. Loaded at runtime by `loader`.

```java
public class MyActorIIAR extends IIActorRef<MyPojo> {

    public MyActorIIAR(String actorName, IIActorSystem system) {
        super(actorName, new MyPojo(), system);
    }

    @Action("doWork")
    public ActionResult doWork(String args) {
        String input = parseFirstArgument(args);
        String output = object.process(input);
        return new ActionResult(true, output);
    }

    @Action("nextItem")
    public ActionResult nextItem(String args) {
        boolean hasNext = object.advance();
        return new ActionResult(hasNext, hasNext ? object.current() : "no more items");
    }
}
```

**Important notes:**
- `parseFirstArgument(args)` safely handles both `"value"` and `["value"]` forms.
- `@Action("methodName")` annotation maps YAML `method:` names to Java methods.
- `ActionResult(true, message)` = success; `ActionResult(false, message)` = failure (triggers alternative transition).
- The `message` of `ActionResult` becomes `${result}` for the next action.
- Return `false` for sentinel conditions ("no more items") so the engine can fall through naturally.

---

## 8. Real-World Example — kana-kanji-pairs.yaml

```yaml
name: kana-kanji-pairs

steps:
  - states: ["0", "1"]
    label: load-plugin
    actions:
      - actor: loader
        method: loadJar
        arguments: "/data/jars/plugin-kana-kanji-1.0.0.jar"

  - states: ["1", "2"]
    label: create-actors
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "vllm", "com.scivicslab.turingworkflow.plugins.kanakanji.VllmActor"]
      - actor: loader
        method: createChild
        arguments: ["ROOT", "ocr", "com.scivicslab.turingworkflow.plugins.kanakanji.OcrActor"]
      - actor: loader
        method: createChild
        arguments: ["ROOT", "pairs", "com.scivicslab.turingworkflow.plugins.kanakanji.PairsActor"]

  - states: ["2", "3"]
    label: configure
    actions:
      - actor: vllm
        method: setUrl
        arguments: "${vllm.url}"
      - actor: vllm
        method: setModel
        arguments: "${vllm.model}"
      - actor: ocr
        method: loadFile
        arguments: "${ocr.file}"
      - actor: pairs
        method: openOutput
        arguments: "${pairs.file}"

  - states: ["3", "4"]
    label: next-page
    actions:
      - actor: ocr
        method: nextPage

  - states: ["3", "end"]
    label: all-done
    actions:
      - actor: pairs
        method: closeOutput

  - states: ["4", "5"]
    label: get-page-text
    actions:
      - actor: ocr
        method: getPageText

  - states: ["5", "6"]
    label: step1-segment
    actions:
      - actor: vllm
        method: segment
        arguments: "${result}"
      - actor: interpreter
        method: putJson
        arguments: {path: segmented, value: "${result}"}

  - states: ["5", "3"]
    label: step1-failed-skip
    actions:
      - actor: log
        method: add
        arguments: {"source": "kana-kanji", "type": "stderr", "data": "Step 1 failed, skipping page"}

  - states: ["8", "3"]
    label: step2-to-hiragana-and-write
    actions:
      - actor: vllm
        method: toHiragana
        arguments: "${segmented}"
      - actor: pairs
        method: writePairs
        arguments: "${result}"

  - states: ["8", "3"]
    label: step2-failed-skip
    actions:
      - actor: log
        method: add
        arguments: {"source": "kana-kanji", "type": "stderr", "data": "Step 2 failed, skipping page"}

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: pairs
        method: closeOutput
      - actor: log
        method: add
        arguments: {"source": "kana-kanji", "type": "stderr", "data": "Workflow ended unexpectedly"}
```

---

## 9. Workflow Templates

### Template A — Minimal linear workflow

```yaml
name: minimal-linear
description: |
  Load plugin, configure, process, done.

steps:
  - states: ["0", "1"]
    label: load-plugin
    actions:
      - actor: loader
        method: loadJar
        arguments: "${plugin.jar}"

  - states: ["1", "2"]
    label: create-actor
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "worker", "${worker.class}"]

  - states: ["2", "3"]
    label: configure
    actions:
      - actor: worker
        method: configure
        arguments: "${config}"

  - states: ["3", "end"]
    label: run
    actions:
      - actor: worker
        method: run
        arguments: "${input}"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: log
        method: add
        arguments: {"source": "workflow", "type": "stderr", "data": "Ended unexpectedly"}
```

### Template B — LLM processing loop with promptBuilder

```yaml
name: llm-loop
description: |
  Load LLM + promptBuilder, build prompt, call agent in a loop.

steps:
  - states: ["0", "1"]
    label: load-plugins
    actions:
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0"
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-prompt-builder:1.0.0"

  - states: ["1", "2"]
    label: create-actors
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "llm", "com.scivicslab.turingworkflow.plugins.llm.LlmActor"]
      - actor: loader
        method: createChild
        arguments: ["ROOT", "promptBuilder", "com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor"]

  - states: ["2", "3"]
    label: configure
    actions:
      - actor: llm
        method: setUrl
        arguments: "${llm.url}"

  - states: ["3", "4"]
    label: build-prompt
    actions:
      - actor: promptBuilder
        method: addWarning
        arguments: "Reply in Japanese."
      - actor: promptBuilder
        method: addMessage
        arguments: "${user.message}"

  - states: ["4", "loop"]
    label: init-counter
    actions:
      - actor: calc
        method: set
        arguments: "0"

  # Loop: get item at index, process, increment
  - states: ["loop", "process"]
    label: get-item
    actions:
      - actor: promptBuilder
        method: getWarning
        arguments: "$(calc.get)"

  - states: ["loop", "5"]
    label: loop-done

  - states: ["process", "loop"]
    label: process-and-next
    actions:
      - actor: llm
        method: prompt
        arguments: "${result}"
      - actor: calc
        method: inc

  - states: ["5", "end"]
    label: finalize
    actions:
      - actor: out
        method: print
        arguments: "All done."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: log
        method: add
        arguments: {"source": "workflow", "type": "stderr", "data": "Ended unexpectedly"}
```

### Template C — Multi-node infrastructure workflow

```yaml
name: multi-node-verify
description: |
  Load inventory, create node actors, run verification on all nodes.

steps:
  - states: ["0", "1"]
    label: load-plugins
    actions:
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-ssh:1.0.0"
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-inventory:1.0.0"

  - states: ["1", "2"]
    label: create-nodegroup
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "nodeGroup", "com.scivicslab.turingworkflow.plugins.inventory.NodeGroupActor"]

  - states: ["2", "3"]
    label: create-node-actors
    actions:
      - actor: nodeGroup
        method: createNodeActors
        arguments: ["web-servers"]

  - states: ["3", "4"]
    label: run-verification
    actions:
      - actor: nodeGroup
        method: apply
        arguments: {"actor": "node-*", "method": "executeCommand", "arguments": ["uptime"]}

  - states: ["4", "end"]
    label: report
    actions:
      - actor: nodeGroup
        method: printSessionSummary

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: log
        method: add
        arguments: {"source": "workflow", "type": "stderr", "data": "Ended unexpectedly"}
```

---

## 10. Generating a New Workflow — Rules for the LLM

When asked to generate a Turing workflow YAML, follow these rules:

1. **Always start at state `"0"` and end at state `"end"`.**
2. **Setup phase first**: load plugin JARs with `loader.loadJar`, create actors with
   `loader.createChild`, configure actors with their init methods.
3. **Loop pattern**: use a pair of transitions from the same state — one that continues
   the loop (advances to the next processing state) and one that exits (goes to `"end"` or a named state)
   when the iteration source is exhausted.
4. **Branching**: place the success transition before the failure transition when both share
   the same `from` state. The engine tries them in document order.
5. **Always include a catch-all**: `states: ["!end", "end"]` at the very end, with cleanup actions.
6. **Use `label:` on every transition** to aid debugging and future patching.
7. **Variable substitution**: use `${varName}` in arguments; pass variables via `-P` on the CLI.
   **Always include a `params:` section** for every `${varName}` placeholder used in the workflow.
   Each entry should have a `description` (shown in the editor UI) and a `default` if a sensible
   default exists. Example: if the workflow uses `${agent}`, add `agent: { description: "Agent name", default: "claude" }`.
8. **Storing inter-step data**: use `interpreter.putJson` / `interpreter.getJson` or
   actor-level `putJson` / `getJson` to pass results between non-adjacent steps.
9. **Actor arguments format**: single value → plain string; multiple values → JSON array
   `["a", "b"]`; structured data → JSON object `{"key": "value"}`.
10. **A transition succeeds when ALL its actions return `ActionResult(true, ...)`**.
    Design actors to return `false` for sentinel conditions (e.g., "no more items") rather
    than throwing exceptions, so the engine can fall through to the exit transition naturally.
11. **Loops**: use `calc` counter (section 5.4) for index-based iteration, or `getNextWarning`/`getNextContext`
    (cursor-based) from `promptBuilder`. Use JEXL state expressions (section 5.5) when the state itself
    is the counter. Never hardcode repeated steps when a loop will do.
12. **`calc`, `list`, `str`, `out` are built-in** — they auto-create on first use, no `loader` needed.
13. **`$(actor.method)` vs `${result}`**: use `$(actor.method)` to read an actor's current value
    as an argument (e.g., `"$(calc.get)"`). Use `${result}` to pass the previous action's output.
14. **Do not embed `${result}` inside a JSON string argument** when the result may contain
    newlines or quotes — use `str:name.escapeJsonStored` (section 5.8) or store with `interpreter.putJson`
    then reference with `${key}`.
15. **Missing plugins**: if a required plugin is not installed, instruct the user to:
    `git clone` the repository, `rm -rf target`, `mvn install` the plugin, then retry.

---

## 11. LLMとしてワークフローを操作する手順

This section is for LLMs (e.g., Claude) interacting with the Turing Workflow system via MCP.
The workflow editor exposes MCP tools through the gateway at `http://localhost:8888`.

### 11.1 Environment

| Component | URL | Role |
|-----------|-----|------|
| MCP Gateway | `http://localhost:8888/mcp/_all` | Tool aggregator; includes `call_agent`, `list_agents`, and all registered server tools |
| Workflow Editor | `http://localhost:8091` | Registered as `workflow-editor` in the gateway; exposes workflow management tools |
| Default LLM Agent | `chat-ui-39500` | LLM agent registered in the gateway; used as target in `callAgent` |

### 11.2 Available MCP Tools (via `workflow-editor`)

| Tool | Arguments | Description |
|------|-----------|-------------|
| `workflow-editor__listWorkflows` | (none) | List all YAML files in `~/works/workflow/` |
| `workflow-editor__loadWorkflow` | `name` | Read a workflow YAML file (shows `${param}` placeholders) |
| `workflow-editor__runWorkflowFile` | `name`, `parametersJson`, `maxIterations` | Load and run a workflow file with parameter substitution |
| `workflow-editor__runWorkflow` | `yaml`, `maxIterations` | Run a YAML workflow string directly (no file, no param substitution) |
| `workflow-editor__importYaml` | `yaml` | Import YAML into the editor UI (for inspection/editing) |
| `workflow-editor__exportYaml` | (none) | Export the currently loaded workflow as YAML |
| `workflow-editor__getStatus` | (none) | Check if a workflow is running |
| `workflow-editor__listActors` | (none) | List all registered actors and their actions |
| `workflow-editor__invokeActor` | `actorName`, `actionName`, `args` | Invoke an actor action directly (without running a full workflow) |
| `workflow-editor__stopWorkflow` | (none) | Stop a running workflow |

### 11.3 Standard Operating Procedure

**Step 1 — Discover available workflows:**
```
Call: workflow-editor__listWorkflows
Result: ask-llm.yaml, chat-via-gateway.yaml, publish-to-github.yaml, ...
```

**Step 2 — Inspect a workflow to understand its parameters:**
```
Call: workflow-editor__loadWorkflow  name="publish-to-github.yaml"
Result: YAML content showing ${repo}, ${dir}, ${task}, ${agent} placeholders
```

**Step 3 — Run the workflow with parameters:**
```
Call: workflow-editor__runWorkflowFile
  name="publish-to-github.yaml"
  parametersJson={"repo":"oogasawa/my-tool","dir":"/home/devteam/works/my-tool","task":"Register this tool as a public GitHub repository","agent":"chat-ui-39500"}
  maxIterations=500
```

**Step 4 — Write and run a new workflow inline:**
```
Call: workflow-editor__runWorkflow
  yaml="<YAML workflow definition>"
  maxIterations=200
```

### 11.4 Environment Parameters Files

Common parameters are stored in `~/works/workflow/environment-parameters/` as YAML files.
Load them before running workflows to avoid repeating the same parameters.

**File: `~/works/workflow/environment-parameters/devteam.yaml`**
```yaml
vars:
  agent: "chat-ui-39500"
```

When running `workflow-editor__runWorkflowFile`, merge these defaults into the `parametersJson`.
Always pass `agent` explicitly unless the workflow has a hardcoded agent name.

### 11.5 `runWorkflowFile` Parameters Reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | string | required | Workflow file name relative to `~/works/workflow/` |
| `parametersJson` | JSON string | `""` | JSON object replacing `${key}` placeholders in the YAML |
| `maxIterations` | int | 100 | Maximum state transitions. Use 500+ for workflows with loops |

**Minimum required parameters for `publish-to-github.yaml`:**
```json
{
  "repo": "owner/repo-name",
  "dir": "/absolute/path/to/local/directory",
  "task": "What to do (in Japanese or English)",
  "agent": "chat-ui-39500"
}
```

### 11.6 Writing a New Workflow as an LLM

1. Read SKILL.md sections 1–10 to understand the YAML structure and available actors.
2. Call `workflow-editor__listActors` to see what actors are currently registered.
3. Draft the YAML workflow following the rules in section 10.
4. Test with `workflow-editor__runWorkflow` (pass YAML directly, no file save needed).
5. If the workflow is reusable, ask the user to save it to `~/works/workflow/`.

**Key points when writing for the `publish-to-github.yaml` pattern:**
- Plugin loading: `plugin-llm` and `plugin-prompt-builder` are in `~/.m2/repository/`
- Gateway URL: always use `http://localhost:8888/mcp/_all` for LLM calls
- Agent name: use `${agent}` (passed as parameter) or hardcode `"chat-ui-39500"`
- `maxIterations`: use at least `(number_of_warnings × 3) + 20` for verify loops

### 11.7 Checking Workflow Status During Execution

The `runWorkflowFile` and `runWorkflow` tools are **synchronous** — they block until completion
or timeout (10 minutes). The result string contains all events in order:

```
[info] Workflow started (YAML)
[fine] State: 1
[fine] State: 2
...
[output] <actor output text>
[finest] Action failed at state '...' transition [...]: <reason>
[completed] Workflow completed
```

If the workflow fails, look for `[finest] Action failed` lines for the root cause.
If it times out, increase `maxIterations` or check for infinite loops.
