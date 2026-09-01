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

/** @returns {Promise<void>} 更新服务在线状态。 */
async function checkHealth() {
  try {
    online.value = await agentApi.health()
  } catch {
    online.value = false
  }
}

/** @returns {Promise<void>} 刷新最近会话及项目文件状态。 */
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

/** @param {'CHAT'|'CODE'} mode 新会话模式。 */
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

/** @param {string} conversationId 目标会话 ID。 */
async function selectConversation(conversationId) {
  if (busy.value || conversationId === selectedConversationId.value) {
    sidebarOpen.value = false
    return
  }
  selectedConversationId.value = conversationId
  messages.value = []
  toolSteps.value = []
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

/** @param {string} conversationId 会话 ID。 */
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

/** @returns {Promise<void>} 向前加载一页历史消息。 */
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

/** @param {string} task 用户任务。 */
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

/** @param {File[]} files 浏览器选择的项目文件。 */
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

/** @returns {Promise<void>} 下载 CODE 会话的完整项目。 */
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

/** @returns {Promise<void>} 打开并加载开发环境快照。 */
async function openEnvironmentDialog() {
  environmentOpen.value = true
  await loadEnvironment(false)
}

/** @param {boolean} refresh 是否强制重新探测。 */
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

/** @param {object} pendingRun 刷新前保存的运行定位信息。 */
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

/** @returns {Promise<boolean>} 是否恢复了活跃运行。 */
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

/** @returns {Promise<void>} 恢复最近终态工具轨迹。 */
async function loadLatestRunTrace(conversationId) {
  try {
    const history = await agentApi.getLatestRun(conversationId)
    toolSteps.value = history?.toolSteps || history?.result?.toolSteps || []
  } catch {
    // 历史消息仍可正常使用。
  }
}

/** @param {object} accepted 运行受理结果。 */
function attachAcceptedRun(accepted) {
  activeRunId.value = accepted.runId
  selectedConversationId.value = accepted.conversationId
  runStatus.value = accepted.status
  busy.value = true
}

/** @param {object} snapshot 运行一致快照。 */
async function attachSnapshot(snapshot) {
  if (activeRunId.value !== snapshot.runId) resetLiveRunOutput()
  selectedConversationId.value = snapshot.conversationId
  applyRunSnapshot(snapshot)
  await refreshConversations()
  await loadMessages(snapshot.conversationId)
  if (isTerminal(snapshot.status)) await finishRun(snapshot)
  else connectRunEvents(snapshot.runId, snapshot.lastSequence || 0)
}

/** @param {string} runId 运行 ID。 @param {number} afterSequence 已处理事件序号。 */
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

/** @param {object} event 后端 SSE 事件。 */
function handleRunEvent(event) {
  if (event.sequence && event.sequence <= lastProcessedRunSequence.value) return
  if (event.sequence) lastProcessedRunSequence.value = event.sequence
  runStatus.value = event.status
  if (event.iteration) currentIteration.value = event.iteration
  if (event.type === 'ITERATION_STARTED') currentToolName.value = ''
  else if (event.type === 'PROGRESS') {
    appendRunActivity({ id: event.sequence, type: event.type.toLowerCase(), message: event.message || '', iteration: event.iteration })
  } else if (event.type === 'REFLECTION_STARTED') {
    upsertRunActivity({ id: `reflection-${event.iteration}`, type: 'reflection', state: 'running', message: event.message || '正在审查当前实现', iteration: event.iteration })
  } else if (event.type === 'REFLECTION_COMPLETED') {
    upsertRunActivity({ id: `reflection-${event.iteration}`, type: 'reflection', state: 'completed', message: event.message || '反思审查完成', iteration: event.iteration })
  } else if (event.type === 'ANSWER_DELTA') streamedAnswer.value += event.message || ''
  else if (event.type === 'ANSWER_RESET') streamedAnswer.value = ''
  else if (event.type === 'TOOL_STARTED') {
    currentToolName.value = event.toolName || ''
    appendRunActivity({ id: event.sequence, type: 'action', toolCallId: event.toolCallId, toolName: event.toolName || 'unknown_tool', detail: event.arguments || '{}', iteration: event.iteration })
  } else if (event.type === 'TOOL_COMPLETED' && event.toolStep) {
    currentToolName.value = ''
    upsertToolStep(event.toolStep)
    appendRunActivity({ id: event.sequence, type: 'observation', toolCallId: event.toolStep.toolCallId, toolName: event.toolStep.toolName || 'unknown_tool', success: event.toolStep.success, detail: event.toolStep.content || event.toolStep.error?.message || '', iteration: event.iteration })
  }
  if (isTerminal(event.status)) finishRun(event)
}

function appendRunActivity(activity) {
  runActivities.value.push({ ...activity, id: activity.id || `${activity.type}-${runActivities.value.length + 1}` })
}

/** @param {object} activity 使用稳定 ID 新增或更新一条运行活动。 */
function upsertRunActivity(activity) {
  const index = runActivities.value.findIndex((item) => item.id === activity.id)
  if (index === -1) appendRunActivity(activity)
  else runActivities.value[index] = { ...runActivities.value[index], ...activity }
}
function resetLiveRunOutput() {
  runActivities.value = []
  streamedAnswer.value = ''
  lastProcessedRunSequence.value = 0
}
function applyRunSnapshot(snapshot) {
  activeRunId.value = snapshot.runId
  runStatus.value = snapshot.status
  currentIteration.value = snapshot.currentIteration || 0
  lastProcessedRunSequence.value = Math.max(lastProcessedRunSequence.value, snapshot.lastSequence || 0)
  toolSteps.value = snapshot.toolSteps || snapshot.result?.toolSteps || []
  streamedAnswer.value = snapshot.liveContent || snapshot.result?.answer || ''
  busy.value = !isTerminal(snapshot.status)
}
function upsertToolStep(step) {
  const index = toolSteps.value.findIndex((item) => item.toolCallId && item.toolCallId === step.toolCallId)
  if (index === -1) toolSteps.value.push(step)
  else toolSteps.value[index] = step
}

/** @returns {Promise<void>} 取消当前运行。 */
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

/** @param {object} payload 终态事件或快照。 */
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
  if (conversationId) await loadMessages(conversationId)
}

function closeRunEvents() { eventSource?.close(); eventSource = null }
function resetRunState(clearTrace = true) {
  closeRunEvents()
  busy.value = false
  cancelling.value = false
  activeRunId.value = null
  runStatus.value = null
  currentIteration.value = 0
  currentToolName.value = ''
  if (clearTrace) { toolSteps.value = []; resetLiveRunOutput() }
}
function isTerminal(status) { return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status) }
function readPendingRun() {
  try { return JSON.parse(sessionStorage.getItem(activeRunStorageKey)) } catch { return null }
}
function writePendingRun(run) { sessionStorage.setItem(activeRunStorageKey, JSON.stringify(run)) }
function clearPendingRun() { sessionStorage.removeItem(activeRunStorageKey) }

function openRenameDialog(conversation) {
  dialogMode.value = 'rename'; dialogTarget.value = conversation; dialogTitle.value = conversation.title
  nextTick(() => dialogInput.value?.select())
}
function openDeleteDialog(conversation) { dialogMode.value = 'delete'; dialogTarget.value = conversation }
function closeDialog() { dialogMode.value = null; dialogTarget.value = null; dialogTitle.value = '' }
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
