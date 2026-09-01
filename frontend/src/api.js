const API_BASE = import.meta.env?.VITE_API_BASE || '/api'

/**
 * 发送请求并统一解析 JSON、文本和 RFC Problem Detail。
 *
 * @param {string} path API 相对路径。
 * @param {RequestInit} [options={}] Fetch 请求选项。
 * @returns {Promise<unknown>} 解析后的响应正文，204 返回 `null`。
 */
async function request(path, options = {}) {
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      ...(options.body && !isFormData ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      ...options.headers,
    },
  })
  if (response.status === 204) return null

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
    throw new Error(body?.detail || body?.message || rawBody || `请求失败（${response.status}）`)
  }
  return body
}

/**
 * 下载二进制响应并触发浏览器保存。
 *
 * @param {string} path 下载接口路径。
 * @param {string} name 不含扩展名的文件名。
 * @returns {Promise<void>}
 */
async function download(path, name) {
  const response = await fetch(`${API_BASE}${path}`)
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
  const safeName = (name || 'project').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_') || 'project'
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${safeName}.zip`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

export const agentApi = {
  /** @returns {Promise<boolean>} 后端健康检查结果。 */
  async health() {
    const response = await fetch(`${API_BASE}/health`)
    return response.ok && (await response.text()) === 'ok'
  },

  /** @returns {Promise<object>} 环境能力快照。 */
  getEnvironment(conversationId = null) {
    const query = conversationId ? `?${new URLSearchParams({ conversationId })}` : ''
    return request(`/environment${query}`)
  },

  /** @returns {Promise<object>} 刷新后的环境能力快照。 */
  refreshEnvironment(conversationId = null) {
    const query = conversationId ? `?${new URLSearchParams({ conversationId })}` : ''
    return request(`/environment/refresh${query}`, { method: 'POST' })
  },

  /** @returns {Promise<object[]>} 最近会话。 */
  listConversations(limit = 100) {
    return request(`/conversations?${new URLSearchParams({ limit: String(limit) })}`)
  },

  /** @returns {Promise<object>} 新建 CHAT 或 CODE 会话。 */
  createConversation(mode, title = '') {
    return request('/conversations', {
      method: 'POST',
      body: JSON.stringify({ mode, title }),
    })
  },

  /** @returns {Promise<object>} 文件导入摘要。 */
  uploadConversationFiles(conversationId, files) {
    const body = new FormData()
    files.forEach((file) => {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    })
    return request(`/conversations/${conversationId}/files`, { method: 'POST', body })
  },

  /** @returns {Promise<void>} 浏览器下载触发后完成。 */
  downloadConversation(conversationId, title) {
    return download(`/conversations/${conversationId}/archive`, title)
  },

  /** @returns {Promise<object>} 分页消息。 */
  getMessages(conversationId, beforeId = null, limit = 50) {
    const params = new URLSearchParams({ limit: String(limit) })
    if (beforeId) params.set('beforeId', String(beforeId))
    return request(`/conversations/${conversationId}/messages?${params}`)
  },

  /** @returns {Promise<object|null>} 最近终态运行。 */
  getLatestRun(conversationId) {
    return request(`/conversations/${conversationId}/latest-run`)
  },

  /** @returns {Promise<object>} 异步运行受理结果。 */
  startRun(requestId, conversationId, mode, task) {
    return request('/agent/runs', {
      method: 'POST',
      body: JSON.stringify({ requestId, conversationId, mode, task }),
    })
  },

  /** @returns {Promise<object>} 运行快照。 */
  getRun(runId) {
    return request(`/agent/runs/${runId}`)
  },

  /** @returns {Promise<object|null>} 会话活跃运行。 */
  getActiveRun(conversationId) {
    return request(`/agent/runs/active?${new URLSearchParams({ conversationId })}`)
  },

  /** @returns {Promise<object>} 取消后的运行快照。 */
  cancelRun(runId) {
    return request(`/agent/runs/${runId}/cancel`, { method: 'POST' })
  },

  /** @returns {string} SSE 订阅地址。 */
  runEventsUrl(runId, afterSequence = 0) {
    const params = new URLSearchParams()
    if (afterSequence > 0) params.set('afterSequence', String(afterSequence))
    return `${API_BASE}/agent/runs/${runId}/events${params.size ? `?${params}` : ''}`
  },

  /** @returns {Promise<object>} 更新后的会话。 */
  renameConversation(conversationId, title) {
    return request(`/conversations/${conversationId}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    })
  },

  /** @returns {Promise<null>} 删除完成后返回 `null`。 */
  deleteConversation(conversationId) {
    return request(`/conversations/${conversationId}`, { method: 'DELETE' })
  },
}
