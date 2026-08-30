# Coding Agent

A Spring Boot and Vue coding agent that uses DeepSeek tool calls to inspect, edit, and verify projects inside a restricted workspace.

## Projects and Conversations

Each managed workspace represents a persistent project. A project can contain multiple conversations, while each conversation is permanently bound to exactly one project. File tools and `execute_command` always resolve paths from that binding.

- `GET /api/workspaces` lists managed projects without exposing server paths. No default project is created.
- `POST /api/workspaces` creates an empty managed workspace from `{ "name": "..." }`.
- `POST /api/workspaces/{workspaceId}/files` imports browser-selected files or folders as multipart data.
- `POST /api/workspaces/{workspaceId}/code` writes pasted code to a workspace-relative path.
- `PATCH /api/workspaces/{workspaceId}` renames its display label.
- `DELETE /api/workspaces/{workspaceId}` removes an unused workspace registration and reclaims it when empty.

The storage container is `${user.dir}/.tmp`. Projects use server-generated UUID directories directly under it, and display names never become filesystem paths. Users must create or select a project before starting a conversation.

The Vue client offers three project initialization modes: create an empty project, upload files or a folder, or paste one source file. Imports reject absolute paths, traversal, symbolic links, duplicate targets, existing files, and requests over the configured count or byte limits. Runs from different conversations in the same project are serialized until Git worktree environments are added; different projects can run in parallel.

## Async Run API

The Vue client uses an asynchronous protocol so an HTTP request does not wait for the full AgentLoop:

1. `POST /api/agent/runs` returns HTTP 202 with a `runId`.
2. `GET /api/agent/runs/{runId}/events` streams replayable SSE events.
3. `GET /api/agent/runs/{runId}` returns the latest status snapshot.
4. `POST /api/agent/runs/{runId}/cancel` cancels a queued or running task.
5. `GET /api/agent/runs/active?conversationId={conversationId}` finds an active conversation run.

The client sends a stable `requestId`, stores the active `runId` in `sessionStorage`, and reconnects after a page refresh. Active runs are retained in memory for one hour by default; restarting the backend ends recovery for unfinished runs.

```cmd
curl.exe -X POST "http://localhost:8123/api/agent/runs" -H "Content-Type: application/json" --data-raw "{\"requestId\":\"demo-1\",\"task\":\"Inspect the project and summarize it. Do not modify files.\"}"
curl.exe -N "http://localhost:8123/api/agent/runs/{runId}/events"
curl.exe "http://localhost:8123/api/agent/runs/{runId}"
curl.exe -X POST "http://localhost:8123/api/agent/runs/{runId}/cancel"
```

## Run Locally

Start the backend with `mvn spring-boot:run`, then start the frontend from `frontend/` with `npm run dev`. Open `http://127.0.0.1:5173/`.
