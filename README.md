# Coding Agent

A local Spring Boot and Vue coding agent powered by DeepSeek tool calls. The project focuses on a transparent ReAct + Reflection loop: perceive the task, reason about the next step, call a tool, observe the result, review the candidate result once, and stop with a verified answer.

## Core Design

- `AgentLoop` controls up to 16 model iterations, tool-call parsing, observations, termination, cancellation, retries, and usage accounting.
- `ReflectionReviewer` performs at most one tool-free final review after a real file change. `PASS` completes the run; `REVISE` returns actionable feedback to the ReAct loop while enough iterations remain.
- `ToolRegistry` exposes structured tool definitions; `ToolExecutor` validates calls and converts failures into model-readable observations.
- File tools read, search, create, edit, and delete project files inside a bounded directory.
- `execute_command` runs allowlisted commands without a shell and supports inline `stdin` or a project-relative `stdin_file`.
- Conversation context is stored in H2 and trimmed by message count and character budget.
- Asynchronous runs return a `runId`; replayable SSE events stream public progress, tool calls, observations, and answer deltas.

The UI deliberately has only two modes:

- `CHAT`: normal conversation with no file or command tools.
- `CODE`: one conversation is one project. Uploads, Agent edits, command execution, and downloads all use that conversation's project directory.

There is no project registry, default workspace, or local-path selector. This keeps project management out of the core Agent implementation.

## Project Files

CODE conversations are stored under:

```text
.tmp/
|-- conversations/
|   `-- {conversationId}/
|       `-- workspace/       # uploaded and Agent-generated project files
`-- imports/                 # short-lived upload staging; never downloaded
```

Folder uploads remove one common outer folder, so the selected project contents become the project root. Imports reject absolute paths, traversal, duplicate targets, symbolic-link escapes, existing files, and oversized requests.

The download endpoint archives only `workspace/`. Internal data, upload staging, H2 files, run metadata, and logs are outside that directory. Regenerable caches such as `.git`, `.gradle`, `.idea`, `node_modules`, `out`, and `target` are explicitly skipped.

## Agent Run API

1. `POST /api/agent/runs` accepts `{ requestId, conversationId, mode, task }` and returns HTTP 202 with a `runId`.
2. `GET /api/agent/runs/{runId}/events` streams replayable SSE events.
3. `GET /api/agent/runs/{runId}` returns the latest snapshot.
4. `POST /api/agent/runs/{runId}/cancel` cancels a queued or running task.
5. `GET /api/agent/runs/active?conversationId=...` recovers the active run after page refresh.

The public ReAct cycle uses `PROGRESS`, `TOOL_STARTED`, `TOOL_COMPLETED`, and `ANSWER_DELTA` events. Final review adds `REFLECTION_STARTED` and `REFLECTION_COMPLETED`. `PROGRESS` and reflection summaries are generated from public execution evidence rather than private chain-of-thought. Tool arguments and observations remain structured and visually distinct in the client.

Reflection is intentionally narrow in the first version. It runs only for a CODE conversation that has successfully called `write_file`, `edit_file`, or `delete_file`, and only when the model is about to return a non-empty final answer. The reviewer receives the original task, candidate answer, changed paths, tool failures, and command-verification evidence. It receives no tools, runs at most once, and reserves two ReAct iterations for a possible correction and new final answer.

Example CODE run:

```cmd
curl.exe -X POST "http://localhost:8123/api/agent/runs" -H "Content-Type: application/json" --data-raw "{\"requestId\":\"demo-1\",\"conversationId\":\"{conversationId}\",\"mode\":\"CODE\",\"task\":\"Inspect the project, fix the failing test, and verify the result.\"}"
curl.exe -N "http://localhost:8123/api/agent/runs/{runId}/events"
```

Conversation project endpoints:

- `POST /api/conversations` creates a `CHAT` or `CODE` conversation.
- `POST /api/conversations/{id}/files` uploads files or a folder into a CODE conversation.
- `POST /api/conversations/{id}/code` imports one pasted UTF-8 source file.
- `GET /api/conversations/{id}/archive` downloads the current project ZIP.

## Safety Boundary

File tools reject absolute paths, traversal, and symbolic-link escapes. Commands are allowlisted, receive argument arrays rather than shell text, and execute in the current CODE project directory. The packaged application includes Java only; other compilers and SDKs are optional host capabilities reported by the environment panel.

Generated programs still run with the current operating-system user's permissions. This is an application-level boundary, not an operating-system sandbox, so use it with trusted inputs.

## Run and Package

Copy `application-local.example.yml` to `application-local.yml`, configure the DeepSeek API key, and run:

```cmd
start.cmd
```

The application opens at `http://127.0.0.1:8123/api/`. Vue is bundled inside the Spring Boot JAR. H2 persists conversations in `data/coding-agent.mv.db`; CODE projects persist under `.tmp/conversations/`.

Build the portable Windows package:

```cmd
build-package.cmd
```

The output is `release/coding-agent-windows.zip`. Maven installs the pinned frontend toolchain, builds Vue, runs tests, packages the JAR, and creates a linked Java runtime. The target machine does not need Java, Node.js, MySQL, or Docker.

For development, start the backend with `mvn spring-boot:run`, run `npm ci` and `npm run dev` in `frontend/`, then open `http://127.0.0.1:5173/`.
