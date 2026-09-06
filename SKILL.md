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
    note: "what this does" # optional: written to execution log; helps trace which step failed
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

### Expression mechanisms — four types

| Where used | Syntax | Engine |
|-----------|--------|--------|
| Action `arguments` | `${varName}` — external param / JSON state | Java + Jackson (dot-path) |
| Action `arguments` | `$(actor.method)` — inline method call | Java (evaluated before action runs) |
| `states` patterns | `*`, `!state`, `a\|b\|c`, `>=5` | Java string matching |
| `states` patterns | `jexl:expr` | Apache Commons JEXL 3 |
| `calc.eval` argument | JEXL expression | Apache Commons JEXL 3 |

`$(actor.method)` is expanded **before** `${varName}`. `${result}` holds the `message` from the
previous `ActionResult` and is substituted as part of `${varName}` processing.

### JEXL expressions in `states`

Both elements of `states` may be JEXL expressions prefixed with `jexl:`.

**Variables set by Turing Workflow before evaluating a `states` JEXL expression:**

| Variable | Type | Value |
|----------|------|-------|
| `state` / `s` | String | Current state string |
| `n` | Number or null | Current state parsed as a number; `null` if not parseable |

If `n` is `null`, numeric comparisons throw an exception — avoid mixing numeric and string states
in the same JEXL expression.

**Numeric shorthand** — available without `jexl:` prefix:

| Shorthand | Equivalent JEXL |
|-----------|-----------------|
| `">=10"` | `"jexl:n >= 10"` |
| `">5"` | `"jexl:n > 5"` |
| `"<3"` | `"jexl:n < 3"` |
| `"<=0"` | `"jexl:n <= 0"` |

`==` and `!=` have no shorthand — use full `jexl:` form.

```yaml
states: ["jexl:n < 10", "jexl:n + 1"]   # loop: state increments each iteration
states: ["jexl:n >= 10", "end"]          # exit when counter reaches 10
states: ["jexl:state == 'error'", "end"] # string comparison
states: [">=10", "end"]                  # shorthand numeric comparison (no jexl: prefix)
```

**`calc.eval` JEXL context** — when `calc.eval` is called, the variable `v` holds the current
value of the `calc` actor. Example: `arguments: "v * 2 + 1"` doubles the counter then adds 1.

---

## 2. CLI Invocation

```bash
java -jar turing-workflow-<version>-shaded.jar run \
  -w <workflow.yaml>           # path to workflow YAML (required)
  -d <base-dir>                # base directory for resolving relative paths (default: .)
  -m <maxIterations>           # max loop iterations (default: 10000)
  -o <overlay-dir>             # kustomize overlay directory (optional)
  -P key=value                 # define a variable; can be repeated
```

**Example:**
```bash
java -jar turing-workflow-4.0.0-shaded.jar run \
  -w /data/workflow/kana-kanji-pairs.yaml \
  -P ocr.file=/data/ocr/book001.tsv \
  -P pairs.file=/data/output/pairs.tsv \
  -P vllm.url=http://192.168.5.16:8000/v1/chat/completions \
  -P vllm.model=Qwen3.5-35B-A3B
```

---

## 3. Built-in Actors

The following actors are always available without any plugin loading.

| Actor | 役割 |
|-------|------|
| `loader` | 外部JARの動的ロードとアクター生成 |
| `log` | 構造化ログエントリの蓄積 |
| `vars` | キーバリューの変数ストア（`-P`で事前投入） |
| `interpreter` | ワークフローエンジン自己参照・JSON状態管理 |
| `calc` / `calc:name` | 数値変数（JEXL式評価対応） |
| `list` / `list:name` | 文字列リスト（`ArrayList<String>`ラッパー） |
| `str` / `str:name` | 文字列変数（JSON安全エスケープ対応） |
| `out` | 標準出力・標準エラー出力 |
| `this` | サブワークフロー呼び出し |

詳細は `reference/built-in-actors.md` を参照

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

## 5. Pattern Library（代表パターン）

### 5.1 Basic linear workflow

```yaml
name: hello-world

steps:
  - states: ["0", "1"]
    label: greet
    note: "print greeting"
    actions:
      - actor: out
        method: print
        arguments: "Hello, Turing Workflow!"

  - states: ["1", "end"]
    label: done
    actions:
      - actor: out
        method: print
        arguments: "Finished."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.4 Conditional branching — by action success/failure

`executeCommand` returns `false` when exit code is non-zero → falls through to the next transition.

```yaml
name: branch-by-command

steps:
  - states: ["0", "1"]
    label: load-ssh-plugin
    actions:
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-ssh:1.0.0"

  - states: ["1", "2"]
    label: create-node
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "localNode", "com.scivicslab.turingworkflow.plugins.ssh.NodeActor"]

  - states: ["2", "success"]
    label: try-command
    note: "exit code 0 → success; non-zero → try next transition"
    actions:
      - actor: localNode
        method: executeCommand
        arguments: ["ls ${target_dir}"]

  - states: ["2", "fallback"]
    label: dir-not-found
    note: "always succeeds → taken when try-command failed"
    actions:
      - actor: out
        method: print
        arguments: "Directory not found, taking fallback."

  - states: ["success", "end"]
    label: success-done
    actions:
      - actor: out
        method: print
        arguments: "Command succeeded."

  - states: ["fallback", "end"]
    label: fallback-done
    actions:
      - actor: out
        method: print
        arguments: "Fallback done."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.8 Loop — JEXL state counter (fixed count, simplest)

```yaml
name: jexl-counter-loop

steps:
  - states: ["jexl:n < 5", "jexl:n + 1"]
    label: loop-body
    note: "n < 5: execute and advance state to n+1"
    actions:
      - actor: out
        method: print
        arguments: "Iteration: ${state}"

  - states: ["jexl:n >= 5", "end"]
    label: loop-exit
    note: "n >= 5: exit"
    actions:
      - actor: out
        method: print
        arguments: "Loop finished."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.10 Loop — `list:` actor full iteration

```yaml
name: list-loop

steps:
  - states: ["0", "1"]
    label: add-items
    note: "populate list (or fill it elsewhere before this step)"
    actions:
      - actor: list:items
        method: add
        arguments: "Apple"
      - actor: list:items
        method: add
        arguments: "Banana"
      - actor: list:items
        method: add
        arguments: "Cherry"

  - states: ["1", "loop"]
    label: setup
    note: "initialize counter"
    actions:
      - actor: calc:i
        method: set
        arguments: "0"

  - states: ["loop", "process"]
    label: get-item
    note: "got item at index i → process"
    actions:
      - actor: list:items
        method: get
        arguments: "$(calc:i.get)"

  - states: ["loop", "end"]
    label: loop-done
    note: "index out of range → all done"
    actions:
      - actor: out
        method: print
        arguments: "All items processed."

  - states: ["process", "loop"]
    label: process-and-next
    note: "${result} holds the item"
    actions:
      - actor: out
        method: print
        arguments: "Processing: ${result}"
      - actor: calc:i
        method: inc

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

詳細は `reference/patterns.md` を参照

---

## 6. Plugin Reference

Plugins are loaded at runtime via the `loader` actor. They must be installed in the local Maven
repository (`~/.m2/repository/`) before use.

| プラグイン | アーティファクト座標 | クラス | アクター名慣例 |
|-----------|-------------------|--------|-------------|
| `plugin-llm` | `com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0` | `com.scivicslab.turingworkflow.plugins.llm.LlmActor` | `llm` |
| `plugin-prompt-builder` | `com.scivicslab.turingworkflow.plugins:plugin-prompt-builder:1.0.0` | `com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor` | `promptBuilder` |
| `plugin-ssh` | `com.scivicslab.turingworkflow.plugins:plugin-ssh:1.0.0` | `com.scivicslab.turingworkflow.plugins.ssh.NodeActor` | `node-<hostname>` |
| `plugin-inventory` | `com.scivicslab.turingworkflow.plugins:plugin-inventory:1.0.0` | `com.scivicslab.turingworkflow.plugins.inventory.NodeGroupActor` | `nodeGroup` |
| `plugin-report` | `com.scivicslab.turingworkflow.plugins:plugin-report:1.0.0` | `com.scivicslab.turingworkflow.plugins.report.ReportBuilderActor` | `reportBuilder` |

詳細は `reference/plugins.md` を参照

---

## 詳細リファレンス

詳細が必要な場合は以下のファイルを Read ツールで参照してください。

- `reference/built-in-actors.md` — 組み込みアクター全メソッド一覧とコード例
- `reference/patterns.md` — 全パターンライブラリ・テンプレート・実例
- `reference/plugins.md` — プラグイン詳細リファレンス・プラグイン開発ガイド

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
16. **Sub-workflow calls**: use `actor: this, method: call, arguments: ["sub.yaml"]` to invoke a
    reusable sub-workflow. Pass parameters via `vars.set` **before** the call — the child shares
    the parent's `vars` actor so all set variables are immediately available as `${varName}` inside
    the sub-workflow. The sub-workflow file is resolved relative to the `-d` base directory.

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
