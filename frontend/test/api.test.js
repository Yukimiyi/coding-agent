import assert from 'node:assert/strict'
import test from 'node:test'

import { agentApi } from '../src/api.js'

test('builds recent conversation URL without workspace filters', async () => {
  let requestedUrl = ''
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url) => {
    requestedUrl = String(url)
    return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const result = await agentApi.listConversations(25)
    assert.deepEqual(result, [])
    assert.equal(requestedUrl, '/api/conversations?limit=25')
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

test('builds conversation environment URLs', async () => {
  const requests = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), method: options.method || 'GET' })
    return new Response('{"checkedAt":"2026-08-31T00:00:00Z","tools":[]}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    await agentApi.getEnvironment('conversation-1')
    await agentApi.refreshEnvironment('conversation-1')
    assert.deepEqual(requests, [
      { url: '/api/environment?conversationId=conversation-1', method: 'GET' },
      { url: '/api/environment/refresh?conversationId=conversation-1', method: 'POST' },
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('surfaces RFC problem details from failed requests', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(
    JSON.stringify({ detail: 'Project still has conversations' }),
    { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
  )
  try {
    await assert.rejects(
      () => agentApi.deleteConversation('conversation-1'),
      /Project still has conversations/,
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})
