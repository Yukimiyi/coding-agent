<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { AlertTriangle, X } from 'lucide-vue-next'
import { agentApi } from './api'
import ChatWorkspace from './components/ChatWorkspace.vue'
import ConversationSidebar from './components/ConversationSidebar.vue'
import EnvironmentDialog from './components/EnvironmentDialog.vue'
import ToolInspector from './components/ToolInspector.vue'

const conversations = ref([])
const selectedConversationId = ref(null)
const messages = ref([])
const toolSteps = ref([])
const runPlan = ref(null)
const latestRun = ref(null)
const runActivities = ref([])
const streamedAnswer = ref('')
const lastProcessedRunSequence = ref(0)
const nextCursor = ref(null)
const hasMoreMessages = ref(false)
const loadingConversations = ref(true)
const loadingMessages = ref(false)
const loadingOlder = ref(false)
const busy = ref(false)
const cancelling = ref(false)
const uploading = ref(false)
const downloading = ref(false)
const activeRunId = ref(null)
const runStatus = ref(null)
const currentIteration = ref(0)
const currentToolName = ref('')
const online = ref(false)
const errorMessage = ref('')
const sidebarOpen = ref(false)
const inspectorOpen = ref(false)
const environmentOpen = ref(false)
const environmentSnapshot = ref(null)
const loadingEnvironment = ref(false)
const dialogMode = ref(null)
const dialogTarget = ref(null)
const dialogTitle = ref('')
const dialogInput = ref(null)

let loadSequence = 0
let eventSource = null
const activeRunStorageKey = 'coding-agent-active-run'

/** 当前选择的完整会话对象；没有选择时为 `null`。 */
const activeConversation = computed(() =>
  conversations.value.find((conversation) => conversation.id === selectedConversationId.value) || null,
)

onMounted(async () => {
  await Promise.all([checkHealth(), refreshConversations()])
  const pendingRun = readPendingRun()
  if (pendingRun) {
    await restorePendingRun(pendingRun)
  } else if (conversations.value.length) {
    await selectConversation(conversations.value[0].id)
  }
})
onBeforeUnmount(closeRunEvents)

/**
 * 请求健康检查并更新服务在线状态；网络异常按离线处理。
 *
 * @returns {Promise<void>} 检查完成后结束。
 */
async function checkHealth() {
  try {
    online.value = await agentApi.health()
  } catch {
    online.value = false
  }
}

/**
 * 刷新最近会话及各 CODE 会话的项目文件状态。
 *
 * @returns {Promise<void>} 列表请求完成后结束。
 */
async function refreshConversations() {
  loadingConversations.value = true
  try {
    conversations.value = await agentApi.listConversations()
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loadingConversations.value = false
  }
}

/**
 * 创建指定模式的会话并立即选中。
 *
 * @param {'CHAT'|'CODE'} mode 新会话模式。
 * @returns {Promise<void>} 会话创建和首次加载完成后结束。
 */
async function createConversation(mode) {
  if (busy.value) return
  errorMessage.value = ''
  try {
    const conversation = await agentApi.createConversation(mode)
    await refreshConversations()
    await selectConversation(conversation.id)
  } catch (error) {
    errorMessage.value = error.message
  }
}

/**
 * 切换会话，清理旧运行视图并恢复目标会话的消息和运行轨迹。
 *
 * @param {string} conversationId 目标会话 ID。
 * @returns {Promise<void>} 目标会话状态加载完成后结束。
 */
async function selectConversation(conversationId) {
  if (busy.value || conversationId === selectedConversationId.value) {
    sidebarOpen.value = false
    return
  }
  selectedConversationId.value = conversationId
  messages.value = []
  toolSteps.value = []
  latestRun.value = null
  resetLiveRunOutput()
  nextCursor.value = null
  hasMoreMessages.value = false
  environmentSnapshot.value = null
  errorMessage.value = ''
  sidebarOpen.value = false
  await loadMessages(conversationId)
  if (!(await restoreConversationRun(conversationId))) {
    await loadLatestRunTrace(conversationId)
  }
}

/**
 * 加载会话最新一页消息，并用序号避免较慢的旧请求覆盖新选择。
 *
 * @param {string} conversationId 会话 ID。
 * @returns {Promise<void>} 分页状态更新完成后结束。
 */
async function loadMessages(conversationId) {
  const sequence = ++loadSequence
  loadingMessages.value = true
  try {
    const page = await agentApi.getMessages(conversationId)
    if (sequence !== loadSequence || conversationId !== selectedConversationId.value) return
    messages.value = page.messages
    nextCursor.value = page.nextCursor
    hasMoreMessages.value = page.hasMore
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    if (sequence === loadSequence) loadingMessages.value = false
  }
}

/**
 * 使用消息游标向前加载一页历史，并保持现有消息顺序。
 *
 * @returns {Promise<void>} 历史页合并完成后结束。
 */
async function loadOlderMessages() {
  if (!selectedConversationId.value || !hasMoreMessages.value || loadingOlder.value) return
  loadingOlder.value = true
  try {
    const page = await agentApi.getMessages(selectedConversationId.value, nextCursor.value)
    messages.value = [...page.messages, ...messages.value]
    nextCursor.value = page.nextCursor
    hasMoreMessages.value = page.hasMore
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loadingOlder.value = false
  }
}

/**
 * 乐观追加用户消息，幂等创建异步 Agent 运行并连接实时事件。
 *
 * @param {string} task 用户输入的任务文本。
 * @returns {Promise<void>} 运行被受理或提交失败后结束。
 */
async function submitTask(task) {
  const conversation = activeConversation.value
  const normalizedTask = task.trim()
  if (!conversation || !normalizedTask || busy.value) return

  const temporaryId = `local-${Date.now()}`
  messages.value.push({
    id: temporaryId,
    conversationId: conversation.id,
    role: 'USER',
    content: normalizedTask,
    status: 'SUCCESS',
    createdAt: new Date().toISOString(),
  })
  busy.value = true
  cancelling.value = false
  runStatus.value = 'QUEUED'
  currentIteration.value = 0
  currentToolName.value = ''
  errorMessage.value = ''
  toolSteps.value = []
  resetLiveRunOutput()

  const pendingRun = {
    requestId: window.crypto?.randomUUID?.() || `request-${Date.now()}`,
    conversationId: conversation.id,
    mode: conversation.mode,
    task: normalizedTask,
  }
  writePendingRun(pendingRun)
  try {
    const accepted = await agentApi.startRun(
      pendingRun.requestId,
      pendingRun.conversationId,
      pendingRun.mode,
      pendingRun.task,
    )
    Object.assign(pendingRun, { runId: accepted.runId, mode: accepted.mode })
    writePendingRun(pendingRun)
    attachAcceptedRun(accepted)
    connectRunEvents(accepted.runId)
  } catch (error) {
    clearPendingRun()
    resetRunState()
    errorMessage.value = error.message
    messages.value.push({
      id: `${temporaryId}-error`, role: 'ASSISTANT', content: `请求失败：${error.message}`,
      status: 'ERROR', createdAt: new Date().toISOString(),
    })
    await checkHealth()
  }
}

/**
 * 将浏览器选择的文件导入当前 CODE 会话工作目录。
 *
 * @param {File[]} files 浏览器选择的项目文件。
 * @returns {Promise<void>} 上传和会话状态刷新完成后结束。
 */
async function uploadFiles(files) {
  const conversation = activeConversation.value
  if (!conversation || conversation.mode !== 'CODE' || busy.value || uploading.value) return
  uploading.value = true
  errorMessage.value = ''
  try {
    await agentApi.uploadConversationFiles(conversation.id, files)
    await refreshConversations()
  } catch (error) {
    errorMessage.value = `上传失败：${error.message}`
  } finally {
    uploading.value = false
  }
}

/**
 * 下载当前 CODE 会话中可见项目文件组成的 ZIP。
 *
 * @returns {Promise<void>} 浏览器下载启动或失败后结束。
 */
async function downloadProject() {
  const conversation = activeConversation.value
  if (!conversation?.artifactAvailable || busy.value || downloading.value) return
  downloading.value = true
  try {
    await agentApi.downloadConversation(conversation.id, conversation.title)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    downloading.value = false
  }
}

/**
 * 打开环境对话框，并优先读取缓存的开发环境快照。
 *
 * @returns {Promise<void>} 首次快照加载完成后结束。
 */
async function openEnvironmentDialog() {
  environmentOpen.value = true
  await loadEnvironment(false)
}

/**
 * 读取宿主环境能力，并在 CODE 会话中叠加项目 Wrapper 状态。
 *
 * @param {boolean} refresh 是否强制重新探测宿主程序版本。
 * @returns {Promise<void>} 环境快照更新完成后结束。
 */
async function loadEnvironment(refresh) {
  if (loadingEnvironment.value) return
  loadingEnvironment.value = true
  try {
    const conversationId = activeConversation.value?.mode === 'CODE' ? activeConversation.value.id : null
    environmentSnapshot.value = refresh
      ? await agentApi.refreshEnvironment(conversationId)
      : await agentApi.getEnvironment(conversationId)
  } catch (error) {
    errorMessage.value = `环境检测失败：${error.message}`
  } finally {
    loadingEnvironment.value = false
  }
}

/**
 * 从会话存储恢复刷新前运行；尚未拿到 runId 时使用 requestId 幂等重试提交。
 *
 * @param {object} pendingRun 刷新前保存的运行定位信息。
 * @returns {Promise<void>} 快照恢复或降级清理完成后结束。
 */
async function restorePendingRun(pendingRun) {
  busy.value = true
  try {
    let snapshot
    if (pendingRun.runId) {
      snapshot = await agentApi.getRun(pendingRun.runId)
    } else {
      const accepted = await agentApi.startRun(
        pendingRun.requestId, pendingRun.conversationId, pendingRun.mode, pendingRun.task,
      )
      Object.assign(pendingRun, { runId: accepted.runId, conversationId: accepted.conversationId, mode: accepted.mode })
      writePendingRun(pendingRun)
      snapshot = await agentApi.getRun(accepted.runId)
    }
    await attachSnapshot(snapshot)
  } catch (error) {
    clearPendingRun()
    resetRunState()
    errorMessage.value = `无法恢复任务：${error.message}`
  }
}

/**
 * 查询并恢复指定会话在后端仍然活跃的运行。
 *
 * @param {string} conversationId 会话 ID。
 * @returns {Promise<boolean>} 找到并恢复活跃运行时为 `true`。
 */
async function restoreConversationRun(conversationId) {
  if (!conversationId || busy.value) return false
  try {
    const snapshot = await agentApi.getActiveRun(conversationId)
    if (!snapshot) return false
    writePendingRun({
      runId: snapshot.runId,
      requestId: snapshot.requestId,
      conversationId: snapshot.conversationId,
      mode: snapshot.mode,
    })
    await attachSnapshot(snapshot)
    return true
  } catch {
    return false
  }
}

/**
 * 加载会话最近一次终态运行，用于刷新后回看工具轨迹和执行记录。
 *
 * @param {string} conversationId 会话 ID。
 * @returns {Promise<void>} 历史运行查询完成后结束；失败不影响消息展示。
 */
async function loadLatestRunTrace(conversationId) {
  try {
    const history = await agentApi.getLatestRun(conversationId)
    latestRun.value = history || null
    toolSteps.value = history?.toolSteps || history?.result?.toolSteps || []
  } catch {
    // 历史消息仍可正常使用。
  }
}

/**
 * 将运行受理结果绑定到当前界面并进入忙碌状态。
 *
 * @param {object} accepted 包含 runId、conversationId 和初始状态的受理结果。
 * @returns {void}
 */
function attachAcceptedRun(accepted) {
  activeRunId.value = accepted.runId
  selectedConversationId.value = accepted.conversationId
  runStatus.value = accepted.status
  busy.value = true
}

/**
 * 应用运行一致快照，恢复消息后根据终态决定收敛或继续订阅 SSE。
 *
 * @param {object} snapshot 后端运行一致快照。
 * @returns {Promise<void>} 快照、会话和订阅状态全部同步后结束。
 */
async function attachSnapshot(snapshot) {
  if (activeRunId.value !== snapshot.runId) resetLiveRunOutput()
  selectedConversationId.value = snapshot.conversationId
  applyRunSnapshot(snapshot)
  await refreshConversations()
  await loadMessages(snapshot.conversationId)
  if (isTerminal(snapshot.status)) await finishRun(snapshot)
  else connectRunEvents(snapshot.runId, snapshot.lastSequence || 0)
}

/**
 * 订阅运行 SSE；连接中断时通过快照接口补偿可能遗漏的状态。
 *
 * @param {string} runId 运行 ID。
 * @param {number} [afterSequence=0] 已处理事件序号，用于断点续传。
 * @returns {void}
 */
function connectRunEvents(runId, afterSequence = 0) {
  closeRunEvents()
  eventSource = new EventSource(agentApi.runEventsUrl(runId, afterSequence))
  eventSource.onmessage = (message) => {
    try {
      handleRunEvent(JSON.parse(message.data))
    } catch {
      errorMessage.value = '收到无法解析的运行事件'
    }
  }
  eventSource.onerror = async () => {
    if (!activeRunId.value) return
    try {
      const snapshot = await agentApi.getRun(activeRunId.value)
      applyRunSnapshot(snapshot)
      if (isTerminal(snapshot.status)) await finishRun(snapshot)
    } catch (error) {
      errorMessage.value = `运行连接中断：${error.message}`
    }
  }
}

/**
 * 按序处理 SSE 事件，并将计划、思考、行动、观察和回答增量映射到界面状态。
 *
 * @param {object} event 后端 SSE 事件。
 * @returns {void}
 */
function handleRunEvent(event) {
  if (event.sequence && event.sequence <= lastProcessedRunSequence.value) return
  if (event.sequence) lastProcessedRunSequence.value = event.sequence
  runStatus.value = event.status
  if (event.iteration) currentIteration.value = event.iteration
  if (event.type === 'ITERATION_STARTED') currentToolName.value = ''
  else if (event.type === 'PLAN_CREATED' || event.type === 'PLAN_UPDATED') {
    if (event.plan) runPlan.value = event.plan
  } else if (event.type === 'PROGRESS') {
    upsertRunActivity({ id: `thought-${event.iteration}`, type: 'thought', summary: event.message || '', iteration: event.iteration })
  } else if (event.type === 'THOUGHT') {
    upsertRunActivity({ id: `thought-${event.iteration}`, type: 'thought', summary: event.message || '', iteration: event.iteration })
  } else if (event.type === 'REFLECTION_STARTED') {
    upsertRunActivity({ id: `result-check-${event.iteration}`, type: 'result_check', state: 'running', summary: '正在核对修改内容和验证结果', iteration: event.iteration })
  } else if (event.type === 'REFLECTION_COMPLETED') {
    const passed = String(event.message || '').startsWith('PASS')
    const reviewSummary = String(event.message || '').replace(/^(PASS|REVISE)\s*·\s*/, '')
    upsertRunActivity({
      id: `result-check-${event.iteration}`,
      type: 'result_check',
      state: passed ? 'completed' : 'revising',
      success: passed,
      summary: passed ? '已核对修改内容和验证结果' : `${reviewSummary || '发现需要调整的内容'}，正在继续修正`,
      iteration: event.iteration,
    })
  } else if (event.type === 'ANSWER_DELTA') streamedAnswer.value += event.message || ''
  else if (event.type === 'ANSWER_RESET') streamedAnswer.value = ''
  else if (event.type === 'TOOL_STARTED') {
    currentToolName.value = event.toolName || ''
    if (event.toolName !== 'update_plan') {
      appendRunActivity({ id: `action-${event.toolCallId}`, type: 'action', toolCallId: event.toolCallId, toolName: event.toolName || 'unknown_tool', detail: event.arguments || '{}', iteration: event.iteration })
    }
  } else if (event.type === 'TOOL_COMPLETED' && event.toolStep) {
    currentToolName.value = ''
    upsertToolStep(event.toolStep)
    if (event.toolStep.toolName !== 'update_plan') {
      appendRunActivity({ id: `observation-${event.toolStep.toolCallId}`, type: 'observation', summary: event.toolStep.success ? '执行成功' : '执行失败', toolCallId: event.toolStep.toolCallId, toolName: event.toolStep.toolName || 'unknown_tool', success: event.toolStep.success, detail: event.toolStep.content || event.toolStep.error?.message || '', iteration: event.iteration })
    }
  }
  if (isTerminal(event.status)) finishRun(event)
}

/**
 * 向公开过程轨迹追加一条活动，缺少 ID 时生成本地稳定 ID。
 *
 * @param {object} activity 思考、行动、观察或结果检查活动。
 * @returns {void}
 */
function appendRunActivity(activity) {
  runActivities.value.push({ ...activity, id: activity.id || `${activity.type}-${runActivities.value.length + 1}` })
}

/**
 * 使用稳定 ID 新增或合并一条运行活动。
 *
 * @param {object} activity 包含稳定 ID 的运行活动。
 * @returns {void}
 */
function upsertRunActivity(activity) {
  const index = runActivities.value.findIndex((item) => item.id === activity.id)
  if (index === -1) appendRunActivity(activity)
  else runActivities.value[index] = { ...runActivities.value[index], ...activity }
}
/** 清空仅属于当前运行的实时过程、计划和回答增量。 @returns {void} */
function resetLiveRunOutput() {
  runActivities.value = []
  runPlan.value = null
  streamedAnswer.value = ''
  lastProcessedRunSequence.value = 0
}
/**
 * 用后端一致快照覆盖可恢复运行状态。
 *
 * @param {object} snapshot 包含状态、序号、工具步骤和公开过程的运行快照。
 * @returns {void}
 */
function applyRunSnapshot(snapshot) {
  activeRunId.value = snapshot.runId
  runStatus.value = snapshot.status
  currentIteration.value = snapshot.currentIteration || 0
  lastProcessedRunSequence.value = Math.max(lastProcessedRunSequence.value, snapshot.lastSequence || 0)
  toolSteps.value = snapshot.toolSteps || snapshot.result?.toolSteps || []
  runPlan.value = snapshot.plan || snapshot.result?.plan || null
  runActivities.value = snapshot.processTrace || snapshot.result?.processTrace || []
  streamedAnswer.value = snapshot.liveContent || snapshot.result?.answer || ''
  busy.value = !isTerminal(snapshot.status)
}
/**
 * 按工具调用 ID 新增或替换一条工具轨迹。
 *
 * @param {object} step 后端返回的工具执行步骤。
 * @returns {void}
 */
function upsertToolStep(step) {
  const index = toolSteps.value.findIndex((item) => item.toolCallId && item.toolCallId === step.toolCallId)
  if (index === -1) toolSteps.value.push(step)
  else toolSteps.value[index] = step
}

/**
 * 请求取消当前运行，并使用返回快照立即收敛界面状态。
 *
 * @returns {Promise<void>} 取消请求和终态同步完成后结束。
 */
async function cancelRun() {
  if (!activeRunId.value || cancelling.value) return
  cancelling.value = true
  try {
    const snapshot = await agentApi.cancelRun(activeRunId.value)
    applyRunSnapshot(snapshot)
    await finishRun(snapshot)
  } catch (error) {
    errorMessage.value = `取消失败：${error.message}`
  } finally {
    cancelling.value = false
  }
}

/**
 * 统一处理完成、失败和取消终态，并刷新持久化消息与最终轨迹。
 *
 * @param {object} payload 终态事件或快照。
 * @returns {Promise<void>} 最终消息和会话列表刷新完成后结束。
 */
async function finishRun(payload) {
  const conversationId = selectedConversationId.value
  closeRunEvents()
  if (payload.result?.toolSteps) toolSteps.value = payload.result.toolSteps
  if (payload.status === 'FAILED') errorMessage.value = payload.message || payload.error || 'Agent 执行失败'
  else if (payload.status === 'CANCELLED') errorMessage.value = '任务已取消'
  else if (payload.result && !payload.result.completed) errorMessage.value = `Agent 已停止：${payload.result.stopReason}`
  clearPendingRun()
  resetRunState(false)
  await new Promise((resolve) => window.setTimeout(resolve, 180))
  await refreshConversations()
  if (conversationId) {
    await loadMessages(conversationId)
    await loadLatestRunTrace(conversationId)
  }
}

/** 关闭当前 SSE 连接并释放浏览器资源。 @returns {void} */
function closeRunEvents() {
  eventSource?.close()
  eventSource = null
}

/**
 * 清理当前活跃运行状态，并按需保留刚完成的过程轨迹。
 *
 * @param {boolean} [clearTrace=true] 是否同时清空工具和公开过程轨迹。
 * @returns {void}
 */
function resetRunState(clearTrace = true) {
  closeRunEvents()
  busy.value = false
  cancelling.value = false
  activeRunId.value = null
  runStatus.value = null
  currentIteration.value = 0
  currentToolName.value = ''
  if (clearTrace) {
    toolSteps.value = []
    resetLiveRunOutput()
  }
}

/**
 * 判断后端运行状态是否已经不可继续变化。
 *
 * @param {string|null} status 运行状态。
 * @returns {boolean} 完成、失败或取消时为 `true`。
 */
function isTerminal(status) {
  return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status)
}

/** @returns {object|null} 刷新前保存的运行定位信息；缺失或损坏时为 `null`。 */
function readPendingRun() {
  try { return JSON.parse(sessionStorage.getItem(activeRunStorageKey)) } catch { return null }
}

/** @param {object} run 可用于幂等恢复的运行定位信息。 @returns {void} */
function writePendingRun(run) {
  sessionStorage.setItem(activeRunStorageKey, JSON.stringify(run))
}

/** 删除已经收敛或无法恢复的运行定位信息。 @returns {void} */
function clearPendingRun() {
  sessionStorage.removeItem(activeRunStorageKey)
}

/**
 * 打开重命名对话框并选中现有标题。
 *
 * @param {object} conversation 待重命名会话。
 * @returns {void}
 */
function openRenameDialog(conversation) {
  dialogMode.value = 'rename'; dialogTarget.value = conversation; dialogTitle.value = conversation.title
  nextTick(() => dialogInput.value?.select())
}

/** @param {object} conversation 待删除会话。 @returns {void} */
function openDeleteDialog(conversation) {
  dialogMode.value = 'delete'
  dialogTarget.value = conversation
}

/** 清空会话操作对话框状态。 @returns {void} */
function closeDialog() {
  dialogMode.value = null
  dialogTarget.value = null
  dialogTitle.value = ''
}

/**
 * 根据当前对话框模式提交重命名或删除操作。
 *
 * @returns {Promise<void>} 操作及会话列表刷新完成后结束。
 */
async function confirmDialog() {
  if (!dialogTarget.value) return
  const target = dialogTarget.value
  try {
    if (dialogMode.value === 'rename') {
      const title = dialogTitle.value.trim()
      if (!title) return
      await agentApi.renameConversation(target.id, title)
    } else {
      await agentApi.deleteConversation(target.id)
      if (selectedConversationId.value === target.id) {
        selectedConversationId.value = null
        messages.value = []
        toolSteps.value = []
      }
    }
    closeDialog()
    await refreshConversations()
  } catch (error) {
    errorMessage.value = error.message
  }
}
</script>

<template>
  <div class="app-shell" :class="{ 'inspector-collapsed': !inspectorOpen }">
    <ConversationSidebar
      :open="sidebarOpen"
      :conversations="conversations"
      :selected-id="selectedConversationId"
      :loading="loadingConversations"
      :busy="busy"
      :online="online"
      @close="sidebarOpen = false"
      @new-chat="createConversation('CHAT')"
      @new-code="createConversation('CODE')"
      @select="selectConversation"
      @rename="openRenameDialog"
      @delete="openDeleteDialog"
    />
    <button v-if="sidebarOpen" class="drawer-backdrop" type="button" title="关闭侧栏" @click="sidebarOpen = false" />

    <section class="workspace-column">
      <div v-if="errorMessage" class="error-banner">
        <AlertTriangle :size="17" /><span>{{ errorMessage }}</span>
        <button type="button" title="关闭" @click="errorMessage = ''"><X :size="16" /></button>
      </div>
      <ChatWorkspace
        :conversation="activeConversation"
        :messages="messages"
        :loading="loadingMessages"
        :loading-older="loadingOlder"
        :has-more="hasMoreMessages"
        :busy="busy"
        :cancelling="cancelling"
        :uploading="uploading"
        :downloading="downloading"
        :run-status="runStatus"
        :current-iteration="currentIteration"
        :current-tool-name="currentToolName"
        :run-activities="runActivities"
        :run-plan="runPlan"
        :latest-run="latestRun"
        :streamed-answer="streamedAnswer"
        @menu="sidebarOpen = true"
        @inspector="inspectorOpen = !inspectorOpen"
        @environment="openEnvironmentDialog"
        @load-older="loadOlderMessages"
        @submit="submitTask"
        @cancel="cancelRun"
        @create-chat="createConversation('CHAT')"
        @create-code="createConversation('CODE')"
        @upload="uploadFiles"
        @download="downloadProject"
      />
    </section>

    <ToolInspector
      :open="inspectorOpen"
      :steps="toolSteps"
      :run-status="runStatus"
      :current-iteration="currentIteration"
      :current-tool-name="currentToolName"
      @close="inspectorOpen = false"
    />

    <EnvironmentDialog
      v-if="environmentOpen"
      :snapshot="environmentSnapshot"
      :loading="loadingEnvironment"
      :workspace-name="activeConversation?.title || ''"
      @close="environmentOpen = false"
      @refresh="loadEnvironment(true)"
    />

    <div v-if="dialogMode" class="dialog-backdrop" @mousedown.self="closeDialog">
      <section class="dialog confirm-dialog" role="dialog" aria-modal="true">
        <button class="icon-button dialog-close" type="button" title="关闭" @click="closeDialog"><X :size="18" /></button>
        <h2>{{ dialogMode === 'rename' ? '重命名会话' : '删除会话' }}</h2>
        <template v-if="dialogMode === 'rename'">
          <input ref="dialogInput" v-model="dialogTitle" maxlength="200" @keydown.enter="confirmDialog" />
        </template>
        <p v-else>删除后，会话消息、运行记录和项目文件都无法恢复。</p>
        <div class="dialog-actions">
          <button class="text-button secondary" type="button" @click="closeDialog">取消</button>
          <button class="text-button" :class="{ danger: dialogMode === 'delete' }" type="button" @click="confirmDialog">
            {{ dialogMode === 'rename' ? '保存' : '删除' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
