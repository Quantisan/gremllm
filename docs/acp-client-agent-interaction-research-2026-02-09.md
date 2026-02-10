# ACP content extraction research (facts only)

Date: 2026-02-09

This document captures only discovered facts from ACP specs, Zed docs, and the local `claude-code-acp` adapter source. It intentionally avoids synthesis or recommendations.

## Sources (external docs)

- ACP Content spec: https://agentclientprotocol.com/protocol/content
- ACP Schema spec: https://agentclientprotocol.com/protocol/schema
- ACP File system spec: https://agentclientprotocol.com/protocol/file-system
- ACP Prompt turn spec: https://agentclientprotocol.com/protocol/prompt-turn
- Zed Agent Panel docs: https://zed.dev/docs/ai/agent-panel
- Zed External Agents docs: https://zed.dev/docs/ai/external-agents

## Facts from ACP specs

- Quote: “Content blocks represent displayable information that flows through the Agent Client Protocol.” (ACP Content spec)
- Quote: “This is the preferred way to include context in prompts.” (ACP Content spec, describing embedded resources)
- Quote: “References to resources that the Agent can access.” (ACP Content spec, describing resource links)
- Quote: “Agent MUST support `ContentBlock::Text` and `ContentBlock::ResourceLink`.” (ACP Schema spec)
- Quote: “allow Agents to read and write text files within the Client’s environment.” (ACP File system spec)
- Quote: “including unsaved changes in the editor.” (ACP File system spec)
- Quote: “The Agent reports the model’s output to the Client via `session/update` notifications.” (ACP Prompt turn spec)
- Quote: “Tool calls can produce different types of content including standard content blocks (text, images) or file diffs.” (ACP Schema spec)

## Facts from Zed docs

- Quote: “You can accept or reject each individual change hunk.” (Zed Agent Panel docs)
- Quote: “Add context by typing `@` in the message editor.” (Zed Agent Panel docs)
- Quote: “Under the hood, Zed runs Claude Code and communicate to it over ACP, through a dedicated adapter.” (Zed External Agents docs)
- Quote: “Zed supports many external agents… through the Agent Client Protocol (ACP).” (Zed External Agents docs)

## Facts from local `claude-code-acp` adapter (source code)

All file references below are in `/Users/paul/Projects/claude-code-acp`.

### Prompt shaping

- `src/acp-agent.ts` converts ACP `resource` blocks into `<context>` tags inside the prompt content.
  - Quote: `text: \`\n<context ref="${chunk.resource.uri}">\n${chunk.resource.text}\n</context>\`,`
- `src/acp-agent.ts` converts `resource_link` blocks into formatted text links.
  - Quote: `text: formattedUri,`
  - Quote: `return \`[@${name}](${uri})\``

### Streaming and content handling

- `src/acp-agent.ts` ignores `document` content blocks when converting model output to ACP notifications.
  - Quote: `case "document":`
  - Quote: `break;`
- `src/acp-agent.ts` emits streaming text chunks as `agent_message_chunk` or `user_message_chunk` updates.
  - Quote: `sessionUpdate: role === "assistant" ? "agent_message_chunk" : "user_message_chunk",`
- `src/acp-agent.ts` forwards tool-result content as `rawOutput` on tool call updates.
  - Quote: `rawOutput: chunk.content,`

### File read behavior (ACP tools)

- `src/mcp-server.ts` labels ACP Read as containing “the most up-to-date contents.”
  - Quote: `always use it instead of Read as it contains the most up-to-date contents.`
- `src/mcp-server.ts` states file reads are truncated for large files and a default size limit is 50KB.
  - Quote: `Any files larger than ${defaults.maxFileSize} bytes will be truncated`
  - Quote: `const defaults = { maxFileSize: 50000, linesToRead: 2000 };`
- `src/mcp-server.ts` appends a `<file-read-info>` marker to read output when truncated or offset.
  - Quote: `readInfo = "\n\n<file-read-info>";`
- `src/utils.ts` enforces the byte limit by slicing content to `maxContentLength`.
  - Quote: `content: fullContent.slice(0, contentLength),`

### File edit behavior (ACP tools)

- `src/mcp-server.ts` generates a unified patch when performing Edit.
  - Quote: `const patch = diff.createPatch(input.file_path, readResponse.content, newContent);`
- `src/mcp-server.ts` returns the patch as text content in the tool result.
  - Quote: `text: patch,`
- `src/mcp-server.ts` Edit tool description says it is for reviewable changes.
  - Quote: `allow the user to conveniently review changes.`
- `src/tools.ts` parses patches and emits ACP `diff` content blocks.
  - Quote: `const patches = diff.parsePatch(toolResult.content[0].text);`
  - Quote: `content.push({ type: "diff", ... });`
- `src/tools.ts` includes locations (file path and line) for diff hunks.
  - Quote: `locations.push({ path: newFileName || oldFileName, line: newStart });`

### Content transformation for Read tool results

- `src/tools.ts` removes the `SYSTEM_REMINDER` string from Read tool outputs before emitting content blocks.
  - Quote: `text: markdownEscape(content.text.replace(SYSTEM_REMINDER, "")),`

### Adapter behavior around allowed/disallowed tools

- `src/acp-agent.ts` disables several built-in tools when ACP-specific tools are available.
  - Quote: `disallowedTools.push("Write", "Edit");`
- `src/acp-agent.ts` explicitly adds ACP read/write/edit tools to `disallowedTools` when built-ins are disabled.
  - Quote: `disallowedTools.push(acpToolNames.read, acpToolNames.write, acpToolNames.edit, ...);`

## Facts from local `claude-code-acp` adapter tests (edge cases)

All file references below are in `/Users/paul/Projects/claude-code-acp`.

### Tool call update behavior

- `src/tests/tools.test.ts` asserts tool_call_update includes raw output for string tool results.
  - Quote: `rawOutput: "hello\n",`
- `src/tests/tools.test.ts` asserts tool_call_update includes raw output for array tool results.
  - Quote: `rawOutput: [{ type: "text", text: "Line 1\nLine 2\nLine 3" }],`
- `src/tests/tools.test.ts` asserts tool_call_update status is `failed` when `is_error` is true.
  - Quote: `status: "failed",`
- `src/tests/tools.test.ts` asserts TodoWrite tool results do not emit tool_call_update notifications.
  - Quote: `expect(notifications).toHaveLength(0);`

### Edit tool result parsing

- `src/tests/acp-agent.test.ts` asserts a successful `mcp__acp__Edit` tool result with non-parseable content returns an empty update.
  - Quote: `expect(update).toEqual({});`
- `src/tests/acp-agent.test.ts` asserts an error `mcp__acp__Edit` tool result yields a content update containing a fenced error string.
  - Quote: `text: "```\nFailed to find \`old_string\`\n```"`

### Read tool labeling with offsets/limits

- `src/tests/acp-agent.test.ts` asserts `mcp__acp__Read` titles include ranges when `limit` is provided.
  - Quote: `title: "Read /Users/test/project/large.txt (1 - 100)",`
- `src/tests/acp-agent.test.ts` asserts `mcp__acp__Read` titles include offset+limit ranges.
  - Quote: `title: "Read /Users/test/project/large.txt (51 - 150)",`
- `src/tests/acp-agent.test.ts` asserts `mcp__acp__Read` titles include “from line” when only `offset` is provided.
  - Quote: `title: "Read /Users/test/project/large.txt (from line 201)",`

### Write tool diff content

- `src/tests/acp-agent.test.ts` asserts Write tool calls produce diff content with `oldText: null`.
  - Quote: `oldText: null,`

### File content truncation behavior

- `src/tests/extract-lines.test.ts` asserts empty content returns `linesRead` as 1.
  - Quote: `expect(result.linesRead).toBe(1);`
- `src/tests/extract-lines.test.ts` asserts byte-limit truncation returns `wasLimited` true.
  - Quote: `expect(result.wasLimited).toBe(true);`
- `src/tests/extract-lines.test.ts` asserts at least one line is returned even if it exceeds the byte limit.
  - Quote: `expect(result.linesRead).toBe(1);`

## Notes on scope

- The “Facts” sections above list observed facts and quotes from sources.
- The “Spike 0 synthesis” section below contains evidence‑backed synthesis and clearly labeled inferences.

---

## Spike 0 synthesis — ACP document‑agent interaction patterns (evidence‑backed)

This section answers the Spike 0 research questions using only evidence from the cited sources. Where a conclusion is an inference, it is labeled as such and includes supporting references.

### 1) Context delivery patterns

- **Per‑message context uses `session/prompt` content blocks.** ACP defines user messages as `ContentBlock[]` and requires clients to respect prompt capabilities when constructing prompts.  
  Refs: https://agentclientprotocol.com/protocol/prompt-turn (Prompt Turn lifecycle), https://agentclientprotocol.com/protocol/schema (PromptRequest, ContentBlock[])

- **Baseline context types are `text` + `resource_link`; embedded `resource` is optional but preferred when supported.** ACP requires agents to support Text and ResourceLink blocks; Resource blocks are preferred for embedded context and depend on the `embeddedContext` capability.  
  Refs: https://agentclientprotocol.com/protocol/schema (PromptRequest + preference for Resource), https://agentclientprotocol.com/protocol/initialization (PromptCapabilities / embeddedContext), https://agentclientprotocol.com/protocol/content (Embedded Resource)

- **Open document state can be provided via file‑system methods (including unsaved editor state).** ACP fs/read_text_file explicitly includes unsaved changes and supports absolute paths with optional line/limit parameters.  
  Refs: https://agentclientprotocol.com/protocol/file-system (read_text_file, unsaved changes, line/limit)

- **Zed’s applied pattern: users explicitly add context via @‑mentions and selection‑as‑context.** Zed allows @‑mentioning files/dirs/symbols/threads/rules/diagnostics; it also supports selection‑as‑context from the editor.  
  Refs: https://zed.dev/docs/ai/agent-panel (Adding Context, Selection as Context)

- **Zed’s applied pattern: multi‑line paste from buffers becomes context automatically.** Zed auto‑formats multi‑line code pasted from a buffer as @‑mentions with file context.  
  Refs: https://zed.dev/docs/ai/agent-panel (Adding Context)

- **claude‑code‑acp adapter: embedded resources become `<context ref="…">` blocks; resource links become formatted links.** The adapter converts ACP `resource` blocks to tagged context appended to the prompt and formats `resource_link` into `[@name](uri)` text.  
  Refs: /Users/paul/Projects/claude-code-acp/src/acp-agent.ts (promptToClaude)

- **Partial‑context handling (inference):** ACP allows line/limit reads and Zed supports selection‑as‑context, so partial document context can be delivered either as embedded snippets or via line‑range reads.  
  Refs: https://agentclientprotocol.com/protocol/file-system (line/limit), https://zed.dev/docs/ai/agent-panel (Selection as Context)

- **System‑prompt / sampling knobs (facts):** ACP exposes session modes that “often affect system prompts,” tool availability, and permission behavior; ACP also provides session config options (e.g., model, mode, thought/reasoning level) for client‑visible selectors.  
  Refs: https://agentclientprotocol.com/protocol/session-modes, https://agentclientprotocol.com/protocol/session-config-options

### 2) Structured response patterns

- **ACP explicitly allows mixed responses (text + tool calls).** The prompt‑turn spec states a model MAY respond with text content, tool calls, or both.  
  Refs: https://agentclientprotocol.com/protocol/prompt-turn (Agent Processing)

- **Structured output travels via `session/update` notifications.** ACP reports model output through `session/update` as message chunks and tool calls.  
  Refs: https://agentclientprotocol.com/protocol/prompt-turn (Agent Reports Output), https://agentclientprotocol.com/protocol/schema (SessionUpdate)

- **ACP supports diff‑shaped outputs in tool call results.** ToolCallContent includes `diff` objects with `path`, `oldText`, and `newText`.  
  Refs: https://agentclientprotocol.com/protocol/schema (ToolCallContent, Diff)

- **claude‑code‑acp adapter emits diffs for edits by parsing unified patches.** The adapter’s Edit tool produces a patch and then parses it into ACP diff blocks with locations for UI display.  
  Refs: /Users/paul/Projects/claude-code-acp/src/mcp-server.ts (diff.createPatch), /Users/paul/Projects/claude-code-acp/src/tools.ts (diff.parsePatch → diff blocks)

- **claude‑code‑acp adapter ignores `document` blocks from model output.** The adapter drops content blocks of type `document` during streaming.  
  Refs: /Users/paul/Projects/claude-code-acp/src/acp-agent.ts (case "document": break;)

- **Tool call updates carry raw output for downstream parsing.** The adapter forwards tool result payloads as `rawOutput` in `tool_call_update` messages.  
  Refs: /Users/paul/Projects/claude-code-acp/src/acp-agent.ts, /Users/paul/Projects/claude-code-acp/src/tests/tools.test.ts

- **Edit tool result parsing failure is explicitly handled.** The adapter returns an empty update when patch parsing fails; error results are converted to content blocks.  
  Refs: /Users/paul/Projects/claude-code-acp/src/tests/acp-agent.test.ts

### 3) Round‑trip interaction model (context → proposed changes → review)

- **Zed supports a review‑changes flow with per‑hunk acceptance/rejection.** After edits, Zed surfaces changed files/lines and allows accepting/rejecting each change hunk.  
  Refs: https://zed.dev/docs/ai/agent-panel (Reviewing Changes)

- **ACP provides diff content + locations for UI review.** ToolCallContent includes diff objects; tool call updates can include file locations for follow‑along UX.  
  Refs: https://agentclientprotocol.com/protocol/schema (ToolCallContent, Diff, ToolCallLocation)

- **claude‑code‑acp edit tool yields diff content + locations, enabling review UIs.** The adapter parses patches into diff blocks and attaches line locations.  
  Refs: /Users/paul/Projects/claude-code-acp/src/tools.ts (diff blocks + locations)

- **Conversation continuity is session‑based.** ACP sessions maintain context/history/state; additional prompts continue the conversation after a turn completes.  
  Refs: https://agentclientprotocol.com/protocol/schema (SessionId), https://agentclientprotocol.com/protocol/prompt-turn (Continue Conversation)

### 4) Practical exploration (evidence‑aligned)

- **Minimal prototype shape (inference):** Use `session/prompt` with `text` + embedded `resource` (when `embeddedContext` is supported) to send document content; observe `session/update` for `agent_message_chunk` plus any `tool_call`/`tool_call_update` entries carrying diff content.  
  Refs: https://agentclientprotocol.com/protocol/prompt-turn, https://agentclientprotocol.com/protocol/content, https://agentclientprotocol.com/protocol/schema

- **Partial‑context prototype (inference):** Use `fs/read_text_file` with `line`/`limit` to send only the annotated region or selection‑as‑context, then request proposed edits and capture diffs via tool calls.  
  Refs: https://agentclientprotocol.com/protocol/file-system, https://zed.dev/docs/ai/agent-panel, https://agentclientprotocol.com/protocol/schema
