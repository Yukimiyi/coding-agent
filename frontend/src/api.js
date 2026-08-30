const API_BASE = import.meta.env?.VITE_API_BASE || '/api'

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

  async downloadWorkspace(workspaceId, workspaceName) {
    const response = await fetch(`${API_BASE}/workspaces/${workspaceId}/archive`)
    if (!response.ok) {
      const rawBody = await response.text()
      let message = rawBody || `下载失败（${response.status}）`
      try {
        const body = JSON.parse(rawBody)
        message = body?.detail || body?.message || message
      } catch {
        // 非 JSON 错误正文直接展示。
      }
      throw new Error(message)
    }
    const blob = await response.blob()
    const safeName = (workspaceName || 'project').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${safeName || 'project'}.zip`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  },

  listConversations(workspaceId = undefined, limit = 100) {
    const params = new URLSearchParams({ limit: String(limit) })
    if (workspaceId === null) {
      params.set('withoutWorkspace', 'true')
    } else if (workspaceId) {
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

  getLatestRun(conversationId) {
    return request(`/conversations/${conversationId}/latest-run`)
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

  runEventsUrl(runId, afterSequence = 0) {
    const params = new URLSearchParams()
    if (afterSequence > 0) {
      params.set('afterSequence', String(afterSequence))
    }
    const query = params.size ? `?${params}` : ''
    return `${API_BASE}/agent/runs/${runId}/events${query}`
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
