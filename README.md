# Coding Agent

A local Spring Boot and Vue coding agent powered by DeepSeek tool calls. The project focuses on a transparent Perception + Plan-and-Solve + ReAct + Reflection workflow: capture a bounded project snapshot, create a public plan, adapt actions to observations, review the candidate result, revise when necessary, and re-review it once before completion.

## Core Design

- `AgentLoop` controls up to 32 ReAct model iterations by default, with at most eight tool calls in each iteration. Every main-model call consumes one iteration, including a turn that only calls `update_plan` or returns final text; initial Planning and standalone Reflection calls are outside this iteration counter. `CODING_AGENT_MAX_ITERATIONS` can override the ReAct limit for a specific machine.
- `ProjectSnapshotProvider` captures a bounded two-level project tree, key build descriptors, and host capabilities before planning. It performs no model call and never modifies files.
- `PlanningService` makes one tool-free model call for CODE tasks and normalizes the result into at most six public, verifiable steps. Invalid structured output receives one format-repair retry; a second failure produces a visible deterministic one-step fallback plan instead of aborting the run.
- `PlanCoordinator` owns the immutable plan state. The model can request changes through `update_plan`, but the coordinator validates state transitions, evidence type, evidence activation window, and cross-step evidence ownership before accepting them.
- `ReflectionReviewer` performs up to two tool-free reviews after a real file change. `PASS` completes the run; `REVISE` returns actionable feedback to the ReAct loop, and the revised candidate may be reviewed once more while enough iterations remain.
- `ToolRegistry` exposes structured tool definitions; `ToolExecutor` validates calls and converts failures into model-readable observations.
- File tools read, search, create, edit, and delete project files inside a bounded directory.
- `execute_command` runs allowlisted commands without a shell and supports inline `stdin` or a project-relative `stdin_file`.
- Conversation context is stored in H2 as a structured rolling summary plus recent complete turns. If summarization fails, context construction falls back to bounded turn and character trimming without failing the current task.
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

Folder uploads remove one common outer folder, so the selected project contents become the project root. Imports reject absolute paths, traversal, duplicate targets, symbolic-link escapes, existing files, and oversized requests. After a successful upload, the client reports the imported file count and total size beside the task composer.

The download endpoint archives only `workspace/`. Internal data, upload staging, H2 files, run metadata, and logs are outside that directory. Regenerable caches such as `.git`, `.gradle`, `.idea`, `node_modules`, `out`, and `target` are explicitly skipped.

## Agent Run API

1. `POST /api/agent/runs` accepts `{ requestId, conversationId, mode, task }` and returns HTTP 202 with a `runId`.
2. `GET /api/agent/runs/{runId}/events` streams replayable SSE events.
3. `GET /api/agent/runs/{runId}` returns the latest snapshot.
4. `POST /api/agent/runs/{runId}/cancel` cancels a queued or running task.
5. `GET /api/agent/runs/active?conversationId=...` recovers the active run after page refresh.

The public lifecycle uses `PERCEPTION_COMPLETED`, `PLAN_STARTED`, `PLAN_CREATED`, `PLAN_UPDATED`, `PROGRESS`, `TOOL_STARTED`, `TOOL_COMPLETED`, and `ANSWER_DELTA` events. Result review adds `REFLECTION_STARTED` and `REFLECTION_COMPLETED`. Plans, progress, and reflection summaries are public work state rather than private chain-of-thought. Tool arguments and observations remain structured and visually distinct in the client.

The plan is a high-level guide, while ReAct remains free to adapt individual actions to tool observations. `update_plan` is an internal run-scoped tool rather than a workspace tool. It accepts only the existing step IDs, permits at most one `IN_PROGRESS` step, and accepts `BLOCKED` only for supported external blockers backed by failed tool evidence. Every step declares `INSPECTION`, `MUTATION`, or `VERIFICATION` evidence. A completed step must reference a matching successful tool call produced after that step entered `IN_PROGRESS`; evidence cannot be replaced on a completed step or reused by another step. The first version deliberately does not perform dynamic replanning.

The terminal `AgentRunResult` contains the final plan, evidence IDs, stop reason, and Reflection round/revision counts. It is stored in the existing H2 `agent_runs.result_json` field. The Vue client restores the latest run after refresh and displays its execution record in a collapsed panel beneath the latest assistant answer.

Reflection is intentionally narrow in the first version. It runs only for a CODE conversation that has successfully called `write_file`, `edit_file`, or `delete_file`, and only when the model is about to return a non-empty final answer. The reviewer receives the original task, candidate answer, changed paths, tool failures, and command-verification evidence, but receives no tools. Up to two reviews are allowed: a first `REVISE` sends actionable feedback back to ReAct, and the resulting candidate may be reviewed once more. A review starts only when enough ReAct iterations remain for correction and a new candidate answer.

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

CODE runs allow 32 ReAct iterations by default so small multi-file projects have room for implementation, recovery, verification, and final review. Set `CODING_AGENT_MAX_ITERATIONS` before startup when a different hard limit is needed; repeated identical tool failures and all existing command limits still apply.

Build the portable Windows package:

```cmd
build-package.cmd
```

The output is `release/coding-agent-windows.zip`; the script recreates the `release` directory when it is absent. Maven installs the pinned frontend toolchain, builds Vue, runs tests, packages the JAR, and creates a linked Java runtime. The target machine does not need Java, Node.js, MySQL, or Docker.

For development, start the backend with `mvn spring-boot:run`, run `npm ci` and `npm run dev` in `frontend/`, then open `http://127.0.0.1:5173/`.
