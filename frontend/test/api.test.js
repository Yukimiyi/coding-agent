import assert from 'node:assert/strict'
import test from 'node:test'

import { agentApi } from '../src/api.js'

test('builds filtered conversation URL', async () => {
  let requestedUrl = ''
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url) => {
    requestedUrl = String(url)
    return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const result = await agentApi.listConversations('workspace-1', 25)
    assert.deepEqual(result, [])
    assert.equal(requestedUrl, '/api/conversations?limit=25&workspaceId=workspace-1')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('uses snapshot sequence when reconnecting to run events', () => {
  assert.equal(
    agentApi.runEventsUrl('run-1', 42),
    '/api/agent/runs/run-1/events?afterSequence=42',
  )
})

test('surfaces RFC problem details from failed requests', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(
    JSON.stringify({ detail: 'Project still has conversations' }),
    { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
  )
  try {
    await assert.rejects(
      () => agentApi.deleteWorkspace('workspace-1'),
      /Project still has conversations/,
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})
