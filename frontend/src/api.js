const API_BASE = import.meta.env.VITE_API_BASE || '/api'

async function request(path, options = {}) {
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      ...(options.body && !isFormData ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
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

  listWorkspaces() {
    return request('/workspaces')
  },

  createWorkspace(name) {
    return request('/workspaces', {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
  },

  uploadWorkspaceFiles(workspaceId, files) {
    const body = new FormData()
    files.forEach((file) => {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    })
    return request(`/workspaces/${workspaceId}/files`, { method: 'POST', body })
  },

  uploadWorkspaceCode(workspaceId, path, content) {
    return request(`/workspaces/${workspaceId}/code`, {
      method: 'POST',
      body: JSON.stringify({ path, content }),
    })
  },

  renameWorkspace(workspaceId, name) {
    return request(`/workspaces/${workspaceId}`, {
      method: 'PATCH',
      body: JSON.stringify({ name }),
    })
  },

  deleteWorkspace(workspaceId) {
    return request(`/workspaces/${workspaceId}`, { method: 'DELETE' })
  },

  listConversations(workspaceId = null, limit = 100) {
    const params = new URLSearchParams({ limit: String(limit) })
    if (workspaceId) {
      params.set('workspaceId', workspaceId)
    }
    return request(`/conversations?${params}`)
  },

  getMessages(conversationId, beforeId = null, limit = 50) {
    const params = new URLSearchParams({ limit: String(limit) })
    if (beforeId) {
      params.set('beforeId', String(beforeId))
    }
    return request(`/conversations/${conversationId}/messages?${params}`)
  },

  startRun(requestId, conversationId, workspaceId, task) {
    return request('/agent/runs', {
      method: 'POST',
      body: JSON.stringify({ requestId, conversationId, workspaceId, task }),
    })
  },

  getRun(runId) {
    return request(`/agent/runs/${runId}`)
  },

  getActiveRun(conversationId) {
    const params = new URLSearchParams({ conversationId })
    return request(`/agent/runs/active?${params}`)
  },

  cancelRun(runId) {
    return request(`/agent/runs/${runId}/cancel`, { method: 'POST' })
  },

  runEventsUrl(runId) {
    return `${API_BASE}/agent/runs/${runId}/events`
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
