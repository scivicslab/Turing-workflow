# Plugin Reference — 詳細リファレンス

全プラグインのメソッド一覧・コード例・開発ガイド。SKILL.md から参照されるドキュメント。

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

**Sub-workflow pattern with `NodeActor.runWorkflow`:**

`NodeActor` can also be used as a local sub-workflow runner. Unlike `this.call` (which shares the
`vars` actor), `NodeActor.runWorkflow` shares the entire `IIActorSystem` — all built-in actors
(`calc:`, `str:`, `list:`, `interpreter`) are shared between parent and child. Pass data by
writing to a named `str:` actor before the call and reading from another after.

```yaml
# Parent workflow
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
      arguments: "${my_param}"

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

**Warning:** Named `calc:` instances (e.g., `calc:i`) are shared between parent and child.
Use distinct names in sub-workflows (e.g., `calc:sub_i`) to avoid counter conflicts.

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

## チュートリアル

各プラグインのYAMLワークフロー例は以下のドキュメントにあります（`doc_SCIVICS002/docs/Turing-workflow-plugins/050_tutorials/`）:

| プラグイン | チュートリアルファイル |
|-----------|----------------------|
| plugin-ssh | 080_PluginSsh_260421_oo01.md |
| plugin-inventory | 090_PluginInventory_260421_oo01.md |
| plugin-llm | 100_PluginLlm_260421_oo01.md |
| plugin-prompt-builder | 110_PluginPromptBuilder_260421_oo01.md |
| plugin-report | 120_PluginReport_260421_oo01.md |
| plugin-secret | 130_PluginSecret_260421_oo01.md |
| plugin-vault | 140_PluginVault_260421_oo01.md |
