# Coding Agent

A Spring Boot and Vue coding agent that uses DeepSeek tool calls to inspect, edit, and verify projects through workspace-scoped tools.

> Security boundary: file tools reject absolute paths, traversal, and symbolic-link escapes. Commands are allowlisted and run without a shell, but generated child programs still run with the current operating-system user's permissions. Use the application only with trusted local projects; it is not an OS-level sandbox.

`execute_command` accepts either inline `stdin` or a workspace-relative `stdin_file` without invoking a shell. Temporary artifacts are removed through the workspace-scoped `delete_file` tool instead of operating-system deletion commands.

## Projects and Conversations

Workspaces represent persistent projects and may be managed by the application or point to a user-selected local directory. A project can contain multiple conversations, while a conversation may also exist without a project for tool-free code discussion. File tools and `execute_command` are exposed only when the conversation has a workspace.

- `GET /api/workspaces` lists projects and their `MANAGED` or `LOCAL` type without exposing server paths. No default project is created.
- `POST /api/workspaces` creates an empty managed workspace from `{ "name": "..." }`.
- `POST /api/workspaces/local` registers an existing local directory for API and CLI clients.
- `POST /api/workspaces/{workspaceId}/files` imports browser-selected files or folders as multipart data.
- `POST /api/workspaces/{workspaceId}/code` writes pasted code to a workspace-relative path.
- `GET /api/workspaces/{workspaceId}/archive` downloads the current managed project as a ZIP archive.
- `PATCH /api/workspaces/{workspaceId}` renames its display label.
- `DELETE /api/workspaces/{workspaceId}` deletes an unused managed project or only unregisters an unused local project.

The managed storage container is `${user.dir}/.tmp`. Managed projects use server-generated UUID directories directly under it, and display names never become filesystem paths. Local projects retain their original directories and are modified in place.

The Vue client can start a pure conversation, create an empty managed project, or upload files and folders. Direct local-directory registration remains available to API and CLI clients. Imports reject absolute paths, traversal, symbolic links, duplicate targets, existing files, and requests over the configured count or byte limits. Runs from different conversations in the same project are serialized; different projects can run in parallel.

## Async Run API

The Vue client uses an asynchronous protocol so an HTTP request does not wait for the full AgentLoop:

1. `POST /api/agent/runs` returns HTTP 202 with a `runId`.
2. `GET /api/agent/runs/{runId}/events` streams replayable SSE events.
3. `GET /api/agent/runs/{runId}` returns the latest status snapshot.
4. `POST /api/agent/runs/{runId}/cancel` cancels a queued or running task.
5. `GET /api/agent/runs/active?conversationId={conversationId}` finds an active conversation run.

The client sends a stable `requestId`, stores the active `runId` in `sessionStorage`, and reconnects after a page refresh. Active runs are retained in memory for one hour by default; restarting the backend ends recovery for unfinished runs.

DeepSeek responses use native streaming. The SSE channel publishes the public Agent cycle as `PERCEPTION`, `THOUGHT`, `TOOL_STARTED`, `TOOL_COMPLETED`, and `ANSWER_DELTA` events, followed by a terminal event. `THOUGHT` contains a short program-generated progress summary rather than the model's private chain of thought. Tool calls and observations use structured payloads, while `ANSWER_DELTA` contains the actual final-answer text chunks. The status snapshot also includes `liveContent` for run recovery.

```cmd
curl.exe -X POST "http://localhost:8123/api/agent/runs" -H "Content-Type: application/json" --data-raw "{\"requestId\":\"demo-1\",\"workspaceId\":\"{workspaceId}\",\"task\":\"Inspect the project and summarize it. Do not modify files.\"}"
curl.exe -N "http://localhost:8123/api/agent/runs/{runId}/events"
curl.exe "http://localhost:8123/api/agent/runs/{runId}"
curl.exe -X POST "http://localhost:8123/api/agent/runs/{runId}/cancel"
```

## Run Locally

The packaged application requires Java 21 or later and does not require MySQL, Docker, or Node.js at runtime. Copy `application-local.example.yml` to `application-local.yml`, set the DeepSeek API key, then run:

```cmd
start.cmd
```

The script starts the executable Spring Boot JAR and opens `http://127.0.0.1:8123/api/`. Vue is bundled inside the JAR. Conversations persist in the local H2 file `data/coding-agent.mv.db`, while managed project files remain under `.tmp/`. Both directories are intentionally excluded from Git.

The local H2 Console is available at `http://127.0.0.1:8123/api/h2-console` with JDBC URL `jdbc:h2:file:./data/coding-agent`, username `sa`, and an empty password. It is bound to the local machine only.

Build a distributable ZIP from source:

```cmd
build-package.cmd
```

The output is `release/coding-agent-windows.zip`. Maven downloads a fixed Node/npm version, runs `npm ci`, builds Vue, executes backend tests, and packages the complete application.

For frontend development, start the backend with `mvn spring-boot:run`, then run `npm ci` followed by `npm run dev` from `frontend/` and open `http://127.0.0.1:5173/`.

## Runtime Limits

- Active SSE runs are kept in memory for a bounded period and use bounded replay buffers. Completed run summaries and tool traces are persisted in H2.
- DeepSeek transient HTTP failures are retried a small number of times. Authentication and configuration errors are returned immediately with a user-facing diagnosis.
- The context window is limited by message count, per-message characters, and total characters. Failed or cancelled turns are excluded from later model context.
