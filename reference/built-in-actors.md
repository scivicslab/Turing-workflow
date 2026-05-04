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

### 3.10 `this` — Sub-workflow invocation

Calls another workflow YAML file as a child interpreter. The child shares the same `IIActorSystem`
as the parent — including the `vars` actor — so variables set with `vars.set` before the call are
visible inside the sub-workflow as `${varName}`.

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

Inside `github-publish.yaml`, `${repo}`, `${dir}`, and `${task}` are available because the
child interpreter reads from the shared `vars` actor.

**Notes:**
- The sub-workflow file path is resolved relative to the `-d` base directory passed on the CLI.
- Actors created inside the sub-workflow (e.g., `llm`, `promptBuilder`) persist in the shared
  `IIActorSystem` after the call returns. On the second call to the same sub-workflow,
  `loader.createChild` for the same actor name may be a no-op or error — design sub-workflows
  to be idempotent when called multiple times, or use uniquely named actors.
- `${result}` after `this.call` holds the final output of the sub-workflow.
