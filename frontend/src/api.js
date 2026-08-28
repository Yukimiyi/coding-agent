const API_BASE = import.meta.env.VITE_API_BASE || '/api'

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      ...options.headers,
    },
  })

  if (response.status === 204) {
    return null
  }

  const rawBody = await response.text()
  let body = null
  if (rawBody) {
    try {
      body = JSON.parse(rawBody)
    } catch {
      body = rawBody
    }
  }

  if (!response.ok) {
    const message = body?.detail || body?.message || rawBody || `请求失败（${response.status}）`
    throw new Error(message)
  }
  return body
}

export const agentApi = {
  async health() {
    const response = await fetch(`${API_BASE}/health`)
    return response.ok && (await response.text()) === 'ok'
  },

  listConversations(limit = 100) {
    return request(`/conversations?limit=${limit}`)
  },

  getMessages(conversationId, beforeId = null, limit = 50) {
    const params = new URLSearchParams({ limit: String(limit) })
    if (beforeId) {
      params.set('beforeId', String(beforeId))
    }
    return request(`/conversations/${conversationId}/messages?${params}`)
  },

  chat(conversationId, task) {
    return request('/agent/chat', {
      method: 'POST',
      body: JSON.stringify({ conversationId, task }),
    })
  },

  renameConversation(conversationId, title) {
    return request(`/conversations/${conversationId}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    })
  },

  deleteConversation(conversationId) {
    return request(`/conversations/${conversationId}`, { method: 'DELETE' })
  },
}
