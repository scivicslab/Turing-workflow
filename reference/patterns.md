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
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.2 External parameters (`params` + `${varName}`)

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
        arguments: "${greeting}, ${username}!"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.3 Inline method call `$(actor.method)`

```yaml
name: calc-demo

steps:
  - states: ["0", "1"]
    label: set-counter
    actions:
      - actor: calc:i
        method: set
        arguments: "42"

  - states: ["1", "end"]
    label: print-counter
    actions:
      - actor: out
        method: print
        arguments: "Counter is $(calc:i.get)"

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

### 5.5 Conditional branching — by JEXL expression on state

```yaml
name: jexl-branch

steps:
  - states: ["jexl:state == 'error'", "handle-error"]
    label: detect-error
    note: "state string equals 'error'"
    actions:
      - actor: out
        method: error
        arguments: "Error state detected."

  - states: ["jexl:state == 'ok'", "handle-ok"]
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
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.6 Conditional branching — by `str` content check

```yaml
name: str-condition

params:
  build:
    description: "Build mode (e.g. 'all' or 'selective')"

steps:
  - states: ["0", "full-mode"]
    label: check-all
    note: "str:mode contains 'all' → full path"
    actions:
      - actor: str:mode
        method: set
        arguments: "${build}"
      - actor: str:mode
        method: contains
        arguments: "all"

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
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
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
        arguments: "${result}"

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

### 5.9 Loop — `calc` counter + actor index exhaustion

`promptBuilder.getWarning(index)` returns `false` when index is out of range.
Any actor method with the same contract can be used in place of `getWarning`.

```yaml
name: calc-counter-loop

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
    note: "initialize counter to 0"
    actions:
      - actor: calc:i
        method: set
        arguments: "0"

  - states: ["loop", "process"]
    label: get-item
    note: "got warning at index i → process"
    actions:
      - actor: promptBuilder
        method: getWarning
        arguments: "$(calc:i.get)"

  - states: ["loop", "end"]
    label: loop-done
    note: "index out of range → all warnings processed"
    actions:
      - actor: out
        method: print
        arguments: "All warnings processed."

  - states: ["process", "loop"]
    label: process-and-next
    note: "${result} holds the warning text"
    actions:
      - actor: out
        method: print
        arguments: "Warning: ${result}"
      - actor: calc:i
        method: inc

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
    note: "${result} holds the warning text"
    actions:
      - actor: out
        method: print
        arguments: "Warning: ${result}"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.12 Sub-workflow — basic call via `NodeActor.runWorkflow`

**Note:** Named `calc:` instances are shared between parent and child — use distinct names in sub-workflows (e.g., `calc:sub_i`).

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
        arguments: "Sub-workflow ended unexpectedly."
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
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.13 Sub-workflow — data passing via shared actors

**process-item.yaml (sub-workflow):**
```yaml
name: process-item

steps:
  - states: ["0", "1"]
    label: read-and-process
    note: "read from str:input"
    actions:
      - actor: out
        method: print
        arguments: "Processing: $(str:input.get)"

  - states: ["1", "end"]
    label: write-output
    note: "write result to str:output for parent to read"
    actions:
      - actor: str:output
        method: set
        arguments: "Processed: $(str:input.get)"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Sub-workflow ended unexpectedly."
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
    note: "write input to shared actor before calling sub-workflow"
    actions:
      - actor: str:input
        method: set
        arguments: "${input_file}"

  - states: ["3", "4"]
    label: run-sub
    actions:
      - actor: localNode
        method: runWorkflow
        arguments: ["/home/devteam/works/workflow/process-item.yaml"]

  - states: ["4", "end"]
    label: read-output
    note: "sub-workflow wrote its result to str:output"
    actions:
      - actor: out
        method: print
        arguments: "Result: $(str:output.get)"

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
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
    actions:
      - actor: calc:i
        method: set
        arguments: "0"

  - states: ["loop", "run-sub"]
    label: get-item
    note: "got item → call sub-workflow"
    actions:
      - actor: list:items
        method: get
        arguments: "$(calc:i.get)"

  - states: ["loop", "end"]
    label: loop-done
    note: "index out of range → done"
    actions:
      - actor: out
        method: print
        arguments: "All items processed."

  - states: ["run-sub", "loop"]
    label: process-item
    note: "set input, call sub-workflow, increment"
    actions:
      - actor: str:input
        method: set
        arguments: "${result}"
      - actor: localNode
        method: runWorkflow
        arguments: ["/home/devteam/works/workflow/process-item.yaml"]
      - actor: calc:i
        method: inc

  - states: ["!end", "end"]
    label: catch-all
    actions:
      - actor: out
        method: error
        arguments: "Workflow ended unexpectedly."
```

---

### 5.15 Safe JSON embedding with `str`

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
