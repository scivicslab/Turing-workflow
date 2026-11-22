# パターンライブラリ・テンプレート・実例

ワークフローの典型パターン全集。SKILL.md から参照されるドキュメント。

---

## 5. Pattern Library

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
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.2 External parameters (`params` + `jexl: state.getString(...)`)

A value given with `-P key=value` is stored in the interpreter's state; read it with
`jexl: state.getString('key')`. (The workflow editor instead replaces `${key}` placeholders in the
YAML text before the run, and does not put the values into the state.)

```yaml
name: greet-user

params:
  username:
    description: "Name to greet"
  greeting:
    description: "Greeting word"
    default: "Hello"

steps:
  - states: ["0", "end"]
    label: greet
    actions:
      - actor: out
        method: print
        arguments: "jexl: state.getString('greeting') + ', ' + state.getString('username') + '!'"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.3 Reading a value from the workflow's own state

```yaml
name: state-demo

steps:
  - states: ["0", "1"]
    label: set-counter
    actions:
      - actor: this
        method: putJson
        arguments: {path: i, value: 42}

  - states: ["1", "end"]
    label: print-counter
    actions:
      - actor: out
        method: print
        arguments: "jexl: 'Counter is ' + state.getInt('i', 0)"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
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
        arguments: ["jexl: 'ls ' + state.getString('target_dir')"]

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
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.5 Conditional branching — by JEXL expression on state

```yaml
name: jexl-branch

steps:
  - states: ["jexl:currentState == 'error'", "handle-error"]
    label: detect-error
    note: "state string equals 'error'"
    actions:
      - actor: out
        method: error
        arguments: "Error state detected."

  - states: ["jexl:currentState == 'ok'", "handle-ok"]
    label: detect-ok
    actions:
      - actor: out
        method: print
        arguments: "OK state detected."

  - states: ["handle-error", "end"]
    label: error-end
    actions:
      - actor: out
        method: print
        arguments: "Handled error."

  - states: ["handle-ok", "end"]
    label: ok-end
    actions:
      - actor: out
        method: print
        arguments: "Handled OK."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.6 Conditional branching — by a text check with `onlyIf`

```yaml
name: text-condition

params:
  build:
    description: "Build mode (e.g. 'all' or 'selective')"

steps:
  - states: ["0", "full-mode"]
    label: check-all
    note: "the build mode contains 'all' → full path"
    actions:
      - actor: this
        method: onlyIf
        arguments: "jexl: state.getString('build', '').contains('all')"

  - states: ["0", "selective-mode"]
    label: fallback-selective
    note: "'all' not found → selective path"
    actions:
      - actor: out
        method: print
        arguments: "Selective mode."

  - states: ["full-mode", "end"]
    label: full-done
    actions:
      - actor: out
        method: print
        arguments: "Full mode processing."

  - states: ["selective-mode", "end"]
    label: selective-done
    actions:
      - actor: out
        method: print
        arguments: "Selective mode processing."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.7 Conditional branching — error-skip in a loop

```yaml
  - states: ["process", "next"]
    label: do-process
    note: "success path"
    actions:
      - actor: worker
        method: process
        arguments: "jexl: result"

  - states: ["process", "loop"]
    label: skip-on-failure
    note: "failure path: log and loop back"
    actions:
      - actor: log
        method: add
        arguments: {"source": "worker", "type": "stderr", "data": "Process failed, skipping."}
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
        arguments: "jexl: 'Iteration: ' + currentState"

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
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.9 Loop — an index in the state + actor index exhaustion

`promptBuilder.getWarning(index)` returns `false` when index is out of range.
Any actor method with the same contract can be used in place of `getWarning`.

```yaml
name: index-loop

steps:
  - states: ["0", "1"]
    label: load-plugins
    actions:
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-prompt-builder:1.0.0"

  - states: ["1", "2"]
    label: create-actor
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "promptBuilder", "com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor"]

  - states: ["2", "3"]
    label: add-warnings
    actions:
      - actor: promptBuilder
        method: addWarning
        arguments: "Reply in Japanese."
      - actor: promptBuilder
        method: addWarning
        arguments: "Be concise."
      - actor: promptBuilder
        method: addWarning
        arguments: "Do not use bullet points."

  - states: ["3", "loop"]
    label: setup
    note: "initialize the index to 0"
    actions:
      - actor: this
        method: putJson
        arguments: {path: i, value: 0}

  - states: ["loop", "process"]
    label: get-item
    note: "got warning at index i → process"
    actions:
      - actor: promptBuilder
        method: getWarning
        arguments: {index: "jexl: state.getInt('i', 0)"}

  - states: ["loop", "end"]
    label: loop-done
    note: "index out of range → all warnings processed"
    actions:
      - actor: out
        method: print
        arguments: "All warnings processed."

  - states: ["process", "loop"]
    label: process-and-next
    note: "result holds the warning text"
    actions:
      - actor: out
        method: print
        arguments: "jexl: 'Warning: ' + result"
      - actor: this
        method: putJson
        arguments: {path: i, value: "jexl: state.getInt('i', 0) + 1"}

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.10 Loop — walking a list held in the workflow's own state

The list and the index are in the workflow's own JSON state. The condition that ends the loop is
written as an action, first in this transition so that nothing has run when it fails. A failed action leaves
the state unchanged, so the next step whose from-pattern matches — `["loop", "end"]` — is the one
that runs. The exit is that transition, not the failure. Do not let a data action's failure stand
in for the condition — `ExitConditionRidingOnAFailure_260914_oo01` says why.

```yaml
name: list-loop

steps:
  - states: ["0", "loop"]
    label: seed
    note: "the list and the index, in this workflow's own state"
    actions:
      - actor: this
        method: appendJson
        arguments: {path: items, value: "Apple"}
      - actor: this
        method: appendJson
        arguments: {path: items, value: "Banana"}
      - actor: this
        method: appendJson
        arguments: {path: items, value: "Cherry"}
      - actor: this
        method: putJson
        arguments: {path: i, value: 0}

  - states: ["loop", "loop"]
    label: take-one
    note: "the check states when the loop ends; it fails when the index reaches the length"
    actions:
      - actor: this
        method: onlyIf
        arguments: "jexl: state.getInt('i',0) < state.select('items').size()"
      - actor: this
        method: print
        arguments: "jexl: 'Processing: ' + state.select('items').get(state.getInt('i',0)).asText()"
      - actor: this
        method: putJson
        arguments: {path: i, value: "jexl: state.getInt('i',0)+1"}

  - states: ["loop", "end"]
    label: nothing-left
    actions:
      - actor: this
        method: print
        arguments: "All items processed."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: this
        method: print
        arguments: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"
```

---

### 5.11 Loop — cursor-based (`getNextWarning` etc.)

```yaml
name: cursor-loop

steps:
  - states: ["0", "1"]
    label: load-plugins
    actions:
      - actor: loader
        method: loadJar
        arguments: "com.scivicslab.turingworkflow.plugins:plugin-prompt-builder:1.0.0"

  - states: ["1", "2"]
    label: create-actor
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "promptBuilder", "com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor"]

  - states: ["2", "loop"]
    label: add-items
    actions:
      - actor: promptBuilder
        method: addWarning
        arguments: "Constraint A"
      - actor: promptBuilder
        method: addWarning
        arguments: "Constraint B"
      - actor: promptBuilder
        method: addWarning
        arguments: "Constraint C"

  - states: ["loop", "process"]
    label: get-next
    note: "got next warning → process"
    actions:
      - actor: promptBuilder
        method: getNextWarning

  - states: ["loop", "end"]
    label: loop-done
    note: "cursor exhausted → done"
    actions:
      - actor: out
        method: print
        arguments: "All warnings processed."

  - states: ["process", "loop"]
    label: process-warning
    note: "result holds the warning text"
    actions:
      - actor: out
        method: print
        arguments: "jexl: 'Warning: ' + result"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.12 Sub-workflow — basic call via `NodeActor.runWorkflow`

**Note:** The parent and the child each keep their own state (`this`). Data passed between them goes through the shared `vars` actor (5.13).

**sub-greeting.yaml:**
```yaml
name: sub-greeting

steps:
  - states: ["0", "end"]
    label: greet
    actions:
      - actor: out
        method: print
        arguments: "Hello from sub-workflow!"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: {message: "jexl: 'Sub-workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

**main.yaml:**
```yaml
name: main-workflow

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

  - states: ["2", "3"]
    label: run-sub
    actions:
      - actor: localNode
        method: runWorkflow
        arguments: ["/home/devteam/works/workflow/sub-greeting.yaml"]

  - states: ["3", "end"]
    label: done
    actions:
      - actor: out
        method: print
        arguments: "Sub-workflow completed."

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.13 Sub-workflow — data passing via `vars`

**process-item.yaml (sub-workflow):**
```yaml
name: process-item

steps:
  - states: ["0", "1"]
    label: read-and-process
    note: "read the input the parent put in vars"
    actions:
      - actor: out
        method: print
        arguments: "jexl: 'Processing: ' + actors.get('vars').get('input')"

  - states: ["1", "end"]
    label: write-output
    note: "put the result in vars for the parent to read"
    actions:
      - actor: vars
        method: set
        arguments: ["output", "jexl: 'Processed: ' + actors.get('vars').get('input')"]

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: {message: "jexl: 'Sub-workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

**main-with-data.yaml (parent):**
```yaml
name: main-with-data

params:
  input_file:
    description: "Path to the file to process"

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

  - states: ["2", "3"]
    label: set-input
    note: "put the input in vars before calling the sub-workflow"
    actions:
      - actor: vars
        method: set
        arguments: ["input", "jexl: state.getString('input_file')"]

  - states: ["3", "4"]
    label: run-sub
    actions:
      - actor: localNode
        method: runWorkflow
        arguments: ["/home/devteam/works/workflow/process-item.yaml"]

  - states: ["4", "end"]
    label: read-output
    note: "the sub-workflow put its result in vars"
    actions:
      - actor: out
        method: print
        arguments: "jexl: 'Result: ' + actors.get('vars').get('output')"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.14 Sub-workflow — called in a loop

```yaml
name: loop-with-sub

steps:
  - states: ["0", "1"]
    label: load-plugins
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

  - states: ["2", "loop"]
    label: init-counter
    note: "the items are in the state at items (filled before this step)"
    actions:
      - actor: this
        method: putJson
        arguments: {path: i, value: 0}

  - states: ["loop", "run-sub"]
    label: take-item
    note: "an item is left at index i → call sub-workflow"
    actions:
      - actor: this
        method: onlyIf
        arguments: "jexl: state.getInt('i', 0) < state.select('items').size()"

  - states: ["loop", "end"]
    label: loop-done
    note: "onlyIf was false → done"
    actions:
      - actor: out
        method: print
        arguments: "All items processed."

  - states: ["run-sub", "loop"]
    label: process-item
    note: "set input, call sub-workflow, increment"
    actions:
      - actor: vars
        method: set
        arguments: ["input", "jexl: state.select('items').get(state.getInt('i', 0)).asText()"]
      - actor: localNode
        method: runWorkflow
        arguments: ["/home/devteam/works/workflow/process-item.yaml"]
      - actor: this
        method: putJson
        arguments: {path: i, value: "jexl: state.getInt('i', 0) + 1"}

  - states: ["!end", "end"]
    label: catch-all
    actions:
      # Record why before ending: the state nothing handled and the last action's result.
      - actor: out
        method: error
        arguments: {message: "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```

---

### 5.15 Safe JSON embedding with a YAML map

Write the arguments as a YAML map whose values are `jexl:` expressions. The engine evaluates each
value on its own and builds the JSON, so a result with quotes or newlines arrives intact.

```yaml
  - states: ["5", "6"]
    label: send
    actions:
      - actor: llm
        method: callAgent
        arguments: {agent: reviewer, prompt: "jexl: result"}
```

---

### 5.16 Sub-workflow calls with `this.call`

```yaml
  - states: ["0", "1"]
    label: setup-project-a
    actions:
      - actor: vars
        method: set
        arguments: ["repo", "owner/project-a"]
      - actor: vars
        method: set
        arguments: ["dir", "/home/devteam/works/project-a"]
      - actor: vars
        method: set
        arguments: ["task", "Build and deploy project-a."]

  - states: ["1", "2"]
    label: publish-project-a
    actions:
      - actor: this
        method: call
        arguments: ["github-publish.yaml"]
```

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
        arguments: "jexl: state.getString('vllm.url')"
      - actor: vllm
        method: setModel
        arguments: "jexl: state.getString('vllm.model')"
      - actor: ocr
        method: loadFile
        arguments: "jexl: state.getString('ocr.file')"
      - actor: pairs
        method: openOutput
        arguments: "jexl: state.getString('pairs.file')"

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
        arguments: "jexl: result"
      - actor: interpreter
        method: putJson
        arguments: {path: segmented, value: "jexl: result"}

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
        arguments: "jexl: state.getString('segmented')"
      - actor: pairs
        method: writePairs
        arguments: "jexl: result"

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
        arguments: {"source": "kana-kanji", "type": "stderr", "data": "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
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
        arguments: "jexl: state.getString('plugin.jar')"

  - states: ["1", "2"]
    label: create-actor
    actions:
      - actor: loader
        method: createChild
        arguments: ["ROOT", "worker", "jexl: state.getString('worker.class')"]

  - states: ["2", "3"]
    label: configure
    actions:
      - actor: worker
        method: configure
        arguments: "jexl: state.getString('config')"

  - states: ["3", "end"]
    label: run
    actions:
      - actor: worker
        method: run
        arguments: "jexl: state.getString('input')"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: log
        method: add
        arguments: {"source": "workflow", "type": "stderr", "data": "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
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
        arguments: "jexl: state.getString('llm.url')"

  - states: ["3", "4"]
    label: build-prompt
    actions:
      - actor: promptBuilder
        method: addWarning
        arguments: "Reply in Japanese."
      - actor: promptBuilder
        method: addMessage
        arguments: "jexl: state.getString('user.message')"

  - states: ["4", "loop"]
    label: init-counter
    actions:
      - actor: this
        method: putJson
        arguments: {path: i, value: 0}

  # Loop: get item at index, process, increment
  - states: ["loop", "process"]
    label: get-item
    actions:
      - actor: promptBuilder
        method: getWarning
        arguments: {index: "jexl: state.getInt('i', 0)"}

  - states: ["loop", "5"]
    label: loop-done

  - states: ["process", "loop"]
    label: process-and-next
    actions:
      - actor: llm
        method: prompt
        arguments: "jexl: result"
      - actor: this
        method: putJson
        arguments: {path: i, value: "jexl: state.getInt('i', 0) + 1"}

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
        arguments: {"source": "workflow", "type": "stderr", "data": "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
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
        arguments: {"source": "workflow", "type": "stderr", "data": "jexl: 'Workflow ended unexpectedly in state ' + currentState + '; last result: ' + result"}
```
