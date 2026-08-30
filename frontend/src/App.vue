<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  AlertTriangle,
  FileUp,
  FolderGit2,
  FolderUp,
  Menu,
  MessageSquareText,
  PanelRight,
  Pencil,
  Plus,
  Trash2,
  X,
} from 'lucide-vue-next'
import { agentApi } from './api'
import ChatWorkspace from './components/ChatWorkspace.vue'
import ConversationSidebar from './components/ConversationSidebar.vue'
import ToolInspector from './components/ToolInspector.vue'

const conversations = ref([])
const workspaces = ref([])
const selectedWorkspaceId = ref(null)
const selectedConversationId = ref(null)
const messages = ref([])
const toolSteps = ref([])
const runActivities = ref([])
const streamedAnswer = ref('')
const lastProcessedRunSequence = ref(0)
const nextCursor = ref(null)
const hasMoreMessages = ref(false)
const loadingConversations = ref(true)
const loadingWorkspaces = ref(true)
const loadingMessages = ref(false)
const loadingOlder = ref(false)
const busy = ref(false)
const cancelling = ref(false)
const activeRunId = ref(null)
const runStatus = ref(null)
const currentIteration = ref(0)
const currentToolName = ref('')
const online = ref(false)
const errorMessage = ref('')
const sidebarOpen = ref(false)
const inspectorOpen = ref(typeof window === 'undefined' || window.innerWidth > 900)
const dialogMode = ref(null)
const dialogTarget = ref(null)
const dialogTitle = ref('')
const dialogInput = ref(null)
const workspaceDialogOpen = ref(false)
const workspaceName = ref('')
const workspaceMode = ref('blank')
const workspaceFiles = ref([])
const workspaceNameInput = ref(null)
const fileInput = ref(null)
const folderInput = ref(null)
const registeringWorkspace = ref(false)
const downloadingWorkspace = ref(false)

let loadSequence = 0
let eventSource = null
const activeRunStorageKey = 'coding-agent-active-run'
const workspaceStorageKey = 'coding-agent-workspace'

const activeConversation = computed(() =>
  conversations.value.find((conversation) => conversation.id === selectedConversationId.value),
)

const activeWorkspace = computed(() =>
  workspaces.value.find((workspace) => workspace.id === selectedWorkspaceId.value),
)

const workspaceTitle = computed(() => activeConversation.value?.title || '新对话')
const workspaceContextLabel = computed(() => activeWorkspace.value?.name || '纯对话')
const dialogIsRename = computed(() => dialogMode.value?.endsWith('rename'))
const dialogIsWorkspace = computed(() => dialogMode.value?.startsWith('workspace'))
const dialogHeading = computed(() => {
  if (dialogIsWorkspace.value) {
    return dialogIsRename.value ? '重命名项目' : '删除项目'
  }
  return dialogIsRename.value ? '重命名对话' : '删除对话'
})

const canCreateWorkspace = computed(() => {
  if (registeringWorkspace.value) {
    return false
  }
  if (workspaceMode.value === 'upload') {
    return workspaceFiles.value.length > 0
  }
  return true
})

const selectedFilesLabel = computed(() => {
  if (workspaceFiles.value.length === 0) {
    return '尚未选择文件'
  }
  if (workspaceFiles.value.length === 1) {
    return workspaceFiles.value[0].webkitRelativePath || workspaceFiles.value[0].name
  }
  return `已选择 ${workspaceFiles.value.length} 个文件`
})

onMounted(async () => {
  await Promise.all([checkHealth(), refreshWorkspaces()])
  const storedWorkspaceId = localStorage.getItem(workspaceStorageKey)
  selectedWorkspaceId.value =
    workspaces.value.find((workspace) => workspace.id === storedWorkspaceId)?.id || null
  await refreshConversations()
  const pendingRun = readPendingRun()
  if (pendingRun) {
    await restorePendingRun(pendingRun)
  } else if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].id)
  }
})

onBeforeUnmount(closeRunEvents)

async function checkHealth() {
  try {
    online.value = await agentApi.health()
  } catch {
    online.value = false
  }
}

async function refreshConversations() {
  loadingConversations.value = true
  try {
    conversations.value = await agentApi.listConversations(selectedWorkspaceId.value)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loadingConversations.value = false
  }
}

async function refreshWorkspaces() {
  loadingWorkspaces.value = true
  try {
    workspaces.value = await agentApi.listWorkspaces()
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loadingWorkspaces.value = false
  }
}

async function selectConversation(conversationId) {
  if (busy.value || conversationId === selectedConversationId.value) {
    sidebarOpen.value = false
    return
  }
  const conversation = conversations.value.find((item) => item.id === conversationId)
  selectedWorkspaceId.value = conversation?.workspaceId || null
  persistWorkspaceSelection(selectedWorkspaceId.value)
  selectedConversationId.value = conversationId
  messages.value = []
  toolSteps.value = []
  resetLiveRunOutput()
  errorMessage.value = ''
  sidebarOpen.value = false
  await loadMessages(conversationId)
  const restoredActiveRun = await restoreConversationRun(conversationId)
  if (!restoredActiveRun) {
    await loadLatestRunTrace(conversationId)
  }
}

async function switchWorkspace(workspaceId) {
  const normalizedWorkspaceId = workspaceId || null
  if (busy.value || normalizedWorkspaceId === selectedWorkspaceId.value) {
    return
  }
  selectedWorkspaceId.value = normalizedWorkspaceId
  persistWorkspaceSelection(normalizedWorkspaceId)
  selectedConversationId.value = null
  messages.value = []
  toolSteps.value = []
  resetLiveRunOutput()
  nextCursor.value = null
  hasMoreMessages.value = false
  errorMessage.value = ''
  sidebarOpen.value = false
  await refreshConversations()
}

async function loadMessages(conversationId) {
  const sequence = ++loadSequence
  loadingMessages.value = true
  try {
    const page = await agentApi.getMessages(conversationId)
    if (sequence !== loadSequence || conversationId !== selectedConversationId.value) {
      return
    }
    messages.value = page.messages
    nextCursor.value = page.nextCursor
    hasMoreMessages.value = page.hasMore
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    if (sequence === loadSequence) {
      loadingMessages.value = false
    }
  }
}

async function loadOlderMessages() {
  if (!selectedConversationId.value || !hasMoreMessages.value || loadingOlder.value) {
    return
  }
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

function startNewConversation() {
  if (busy.value) {
    return
  }
  selectedConversationId.value = null
  messages.value = []
  toolSteps.value = []
  resetLiveRunOutput()
  nextCursor.value = null
  hasMoreMessages.value = false
  errorMessage.value = ''
  sidebarOpen.value = false
}

async function submitTask(task) {
  const normalizedTask = task.trim()
  if (!normalizedTask || busy.value) {
    return
  }

  const requestedConversationId = selectedConversationId.value
  const temporaryId = `local-${Date.now()}`
  messages.value.push({
    id: temporaryId,
    conversationId: requestedConversationId,
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
    conversationId: requestedConversationId,
    workspaceId: selectedWorkspaceId.value,
    task: normalizedTask,
  }
  writePendingRun(pendingRun)

  try {
    const response = await agentApi.startRun(
      pendingRun.requestId,
      requestedConversationId,
      pendingRun.workspaceId,
      normalizedTask,
    )
    pendingRun.runId = response.runId
    pendingRun.conversationId = response.conversationId
    pendingRun.workspaceId = response.workspaceId
    writePendingRun(pendingRun)
    attachAcceptedRun(response)
    await refreshConversations()
    connectRunEvents(response.runId)
  } catch (error) {
    clearPendingRun()
    resetRunState()
    errorMessage.value = error.message
    messages.value.push({
      id: `${temporaryId}-error`,
      conversationId: requestedConversationId,
      role: 'ASSISTANT',
      content: `请求失败：${error.message}`,
      status: 'ERROR',
      createdAt: new Date().toISOString(),
    })
    await checkHealth()
  }
}

async function restorePendingRun(pendingRun) {
  busy.value = true
  try {
    let snapshot
    if (pendingRun.runId) {
      snapshot = await agentApi.getRun(pendingRun.runId)
    } else {
      const accepted = await agentApi.startRun(
        pendingRun.requestId,
        pendingRun.conversationId,
        pendingRun.workspaceId,
        pendingRun.task,
      )
      pendingRun.runId = accepted.runId
      pendingRun.conversationId = accepted.conversationId
      pendingRun.workspaceId = accepted.workspaceId
      writePendingRun(pendingRun)
      snapshot = await agentApi.getRun(accepted.runId)
    }
    await attachSnapshot(snapshot)
  } catch (error) {
    clearPendingRun()
    resetRunState()
    errorMessage.value = `无法恢复任务：${error.message}`
    if (conversations.value.length > 0) {
      await selectConversation(conversations.value[0].id)
    }
  }
}

async function restoreConversationRun(conversationId) {
  if (!conversationId || busy.value) {
    return false
  }
  try {
    const snapshot = await agentApi.getActiveRun(conversationId)
    if (snapshot) {
      writePendingRun({
        runId: snapshot.runId,
        requestId: snapshot.requestId,
        conversationId: snapshot.conversationId,
        workspaceId: snapshot.workspaceId,
      })
      await attachSnapshot(snapshot)
      return true
    }
  } catch {
    // 对话历史仍可使用，活跃运行恢复失败不阻塞页面。
  }
  return false
}

async function loadLatestRunTrace(conversationId) {
  try {
    const history = await agentApi.getLatestRun(conversationId)
    toolSteps.value = history?.toolSteps || history?.result?.toolSteps || []
  } catch {
    // 历史消息仍可使用，轨迹恢复失败不阻塞会话。
  }
}

function attachAcceptedRun(accepted) {
  activeRunId.value = accepted.runId
  selectedConversationId.value = accepted.conversationId
  selectedWorkspaceId.value = accepted.workspaceId || null
  persistWorkspaceSelection(selectedWorkspaceId.value)
  runStatus.value = accepted.status
  busy.value = true
}

async function attachSnapshot(snapshot) {
  if (activeRunId.value !== snapshot.runId) {
    resetLiveRunOutput()
  }
  selectedWorkspaceId.value = snapshot.workspaceId || null
  persistWorkspaceSelection(selectedWorkspaceId.value)
  selectedConversationId.value = snapshot.conversationId
  applyRunSnapshot(snapshot)
  await refreshConversations()
  await loadMessages(snapshot.conversationId)
  if (isTerminal(snapshot.status)) {
    await finishRun(snapshot)
  } else {
    connectRunEvents(snapshot.runId, snapshot.lastSequence || 0)
  }
}

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
    if (!activeRunId.value) {
      return
    }
    try {
      const snapshot = await agentApi.getRun(activeRunId.value)
      applyRunSnapshot(snapshot)
      if (isTerminal(snapshot.status)) {
        await finishRun(snapshot)
      }
    } catch (error) {
      errorMessage.value = `运行连接中断：${error.message}`
    }
  }
}

function handleRunEvent(event) {
  if (event.sequence && event.sequence <= lastProcessedRunSequence.value) {
    return
  }
  if (event.sequence) {
    lastProcessedRunSequence.value = event.sequence
  }
  runStatus.value = event.status
  if (event.iteration) {
    currentIteration.value = event.iteration
  }
  if (event.type === 'ITERATION_STARTED') {
    currentToolName.value = ''
  } else if (event.type === 'PERCEPTION' || event.type === 'THOUGHT') {
    appendRunActivity({
      id: event.sequence,
      type: event.type.toLowerCase(),
      message: event.message || '',
      iteration: event.iteration,
    })
  } else if (event.type === 'ANSWER_DELTA') {
    streamedAnswer.value += event.message || ''
  } else if (event.type === 'ANSWER_RESET') {
    streamedAnswer.value = ''
  } else if (event.type === 'TOOL_STARTED') {
    currentToolName.value = event.toolName || ''
    appendRunActivity({
      id: event.sequence,
      type: 'action',
      toolCallId: event.toolCallId,
      toolName: event.toolName || 'unknown_tool',
      detail: event.arguments || '{}',
      iteration: event.iteration,
    })
  } else if (event.type === 'TOOL_COMPLETED' && event.toolStep) {
    currentToolName.value = ''
    upsertToolStep(event.toolStep)
    appendRunActivity({
      id: event.sequence,
      type: 'observation',
      toolCallId: event.toolStep.toolCallId,
      toolName: event.toolStep.toolName || 'unknown_tool',
      success: event.toolStep.success,
      detail: event.toolStep.content || event.toolStep.error?.message || '',
      iteration: event.iteration,
    })
  }
  if (isTerminal(event.status)) {
    finishRun(event)
  }
}

function appendRunActivity(activity) {
  runActivities.value.push({
    ...activity,
    id: activity.id || `${activity.type}-${runActivities.value.length + 1}`,
  })
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
  lastProcessedRunSequence.value = Math.max(
    lastProcessedRunSequence.value,
    snapshot.lastSequence || 0,
  )
  toolSteps.value = snapshot.toolSteps || snapshot.result?.toolSteps || []
  streamedAnswer.value = snapshot.liveContent || snapshot.result?.answer || ''
  busy.value = !isTerminal(snapshot.status)
}

function upsertToolStep(step) {
  const existingIndex = toolSteps.value.findIndex(
    (item) => item.toolCallId && item.toolCallId === step.toolCallId,
  )
  if (existingIndex === -1) {
    toolSteps.value.push(step)
  } else {
    toolSteps.value[existingIndex] = step
  }
}

async function cancelRun() {
  if (!activeRunId.value || cancelling.value) {
    return
  }
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

async function finishRun(payload) {
  const conversationId = selectedConversationId.value
  closeRunEvents()
  if (payload.result?.toolSteps) {
    toolSteps.value = payload.result.toolSteps
  }
  if (payload.status === 'FAILED') {
    errorMessage.value = payload.message || payload.error || 'Agent 执行失败'
  } else if (payload.status === 'CANCELLED') {
    errorMessage.value = '任务已取消'
  } else if (payload.result && !payload.result.completed) {
    errorMessage.value = `Agent 已停止：${payload.result.stopReason}`
  }
  clearPendingRun()
  resetRunState(false)
  await new Promise((resolve) => window.setTimeout(resolve, 180))
  await refreshConversations()
  if (conversationId) {
    await loadMessages(conversationId)
  }
}

function closeRunEvents() {
  eventSource?.close()
  eventSource = null
}

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

function isTerminal(status) {
  return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status)
}

function readPendingRun() {
  try {
    return JSON.parse(sessionStorage.getItem(activeRunStorageKey))
  } catch {
    return null
  }
}

function writePendingRun(run) {
  sessionStorage.setItem(activeRunStorageKey, JSON.stringify(run))
}

function clearPendingRun() {
  sessionStorage.removeItem(activeRunStorageKey)
}

function persistWorkspaceSelection(workspaceId) {
  if (workspaceId) {
    localStorage.setItem(workspaceStorageKey, workspaceId)
  } else {
    localStorage.removeItem(workspaceStorageKey)
  }
}

function openWorkspaceDialog() {
  workspaceName.value = ''
  workspaceMode.value = 'blank'
  workspaceFiles.value = []
  sidebarOpen.value = false
  workspaceDialogOpen.value = true
  nextTick(() => workspaceNameInput.value?.focus())
}

function closeWorkspaceDialog() {
  if (registeringWorkspace.value) {
    return
  }
  workspaceDialogOpen.value = false
  workspaceName.value = ''
  workspaceFiles.value = []
}

function selectWorkspaceFiles(event) {
  workspaceFiles.value = Array.from(event.target.files || [])
}

async function createWorkspace() {
  if (!canCreateWorkspace.value) {
    return
  }
  registeringWorkspace.value = true
  let workspace = null
  try {
    workspace = await agentApi.createWorkspace(workspaceName.value.trim())
    if (workspaceMode.value === 'upload') {
      await agentApi.uploadWorkspaceFiles(workspace.id, workspaceFiles.value)
    }
    await refreshWorkspaces()
    workspaceDialogOpen.value = false
    await switchWorkspace(workspace.id)
  } catch (error) {
    if (workspace?.type === 'MANAGED') {
      try {
        await agentApi.deleteWorkspace(workspace.id)
      } catch {
        // 创建失败后的清理由后端状态决定，保留最初的错误信息。
      }
    }
    errorMessage.value = error.message
  } finally {
    registeringWorkspace.value = false
  }
}

async function downloadWorkspace() {
  if (!activeWorkspace.value || busy.value || downloadingWorkspace.value) {
    return
  }
  downloadingWorkspace.value = true
  errorMessage.value = ''
  try {
    await agentApi.downloadWorkspace(activeWorkspace.value.id, activeWorkspace.value.name)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    downloadingWorkspace.value = false
  }
}

function openRenameDialog(conversation) {
  dialogMode.value = 'conversation-rename'
  dialogTarget.value = conversation
  dialogTitle.value = conversation.title
  nextTick(() => dialogInput.value?.select())
}

function openDeleteDialog(conversation) {
  dialogMode.value = 'conversation-delete'
  dialogTarget.value = conversation
}

function openRenameWorkspaceDialog(workspace) {
  dialogMode.value = 'workspace-rename'
  dialogTarget.value = workspace
  dialogTitle.value = workspace.name
  nextTick(() => dialogInput.value?.select())
}

function openDeleteWorkspaceDialog(workspace) {
  dialogMode.value = 'workspace-delete'
  dialogTarget.value = workspace
}

function closeDialog() {
  dialogMode.value = null
  dialogTarget.value = null
  dialogTitle.value = ''
}

async function confirmDialog() {
  if (!dialogTarget.value) {
    return
  }
  const target = dialogTarget.value
  try {
    if (dialogMode.value === 'conversation-rename') {
      const title = dialogTitle.value.trim()
      if (!title) {
        return
      }
      await agentApi.renameConversation(target.id, title)
    } else if (dialogMode.value === 'conversation-delete') {
      await agentApi.deleteConversation(target.id)
      if (selectedConversationId.value === target.id) {
        startNewConversation()
      }
    } else if (dialogMode.value === 'workspace-rename') {
      const name = dialogTitle.value.trim()
      if (!name) {
        return
      }
      await agentApi.renameWorkspace(target.id, name)
      await refreshWorkspaces()
    } else if (dialogMode.value === 'workspace-delete') {
      await agentApi.deleteWorkspace(target.id)
      await refreshWorkspaces()
      if (selectedWorkspaceId.value === target.id) {
        await switchWorkspace(null)
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
      :workspaces="workspaces"
      :selected-workspace-id="selectedWorkspaceId"
      :selected-id="selectedConversationId"
      :loading="loadingConversations"
      :workspace-loading="loadingWorkspaces"
      :busy="busy"
      :online="online"
      :downloading-workspace="downloadingWorkspace"
      @close="sidebarOpen = false"
      @new="startNewConversation"
      @select="selectConversation"
      @rename="openRenameDialog"
      @delete="openDeleteDialog"
      @workspace-change="switchWorkspace"
      @add-workspace="openWorkspaceDialog"
      @download-workspace="downloadWorkspace"
      @rename-workspace="openRenameWorkspaceDialog"
      @delete-workspace="openDeleteWorkspaceDialog"
    />

    <section class="workspace-column">
      <header class="workspace-header">
        <button class="icon-button mobile-only" type="button" title="打开对话列表" @click="sidebarOpen = true">
          <Menu :size="19" />
        </button>
        <div class="workspace-heading">
          <h1>{{ workspaceTitle }}</h1>
          <span class="workspace-context">{{ workspaceContextLabel }}</span>
          <span class="header-status" :class="{ offline: !online }">
            <span class="status-dot" />
            {{ online ? '服务在线' : '服务离线' }}
          </span>
        </div>
        <div class="header-actions">
          <button class="icon-button" type="button" title="新建对话" :disabled="busy" @click="startNewConversation">
            <Plus :size="19" />
          </button>
          <button
            class="icon-button"
            :class="{ active: inspectorOpen }"
            type="button"
            title="切换工具轨迹"
            @click="inspectorOpen = !inspectorOpen"
          >
            <PanelRight :size="19" />
          </button>
        </div>
      </header>

      <ChatWorkspace
        :messages="messages"
        :busy="busy"
        :loading="loadingMessages"
        :loading-older="loadingOlder"
        :has-more="hasMoreMessages"
        :error-message="errorMessage"
        :run-status="runStatus"
        :current-iteration="currentIteration"
        :current-tool-name="currentToolName"
        :activities="runActivities"
        :streamed-answer="streamedAnswer"
        :cancelling="cancelling"
        :workspace-active="Boolean(selectedWorkspaceId)"
        @send="submitTask"
        @cancel="cancelRun"
        @load-older="loadOlderMessages"
        @dismiss-error="errorMessage = ''"
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

    <button
      v-if="sidebarOpen"
      class="drawer-backdrop mobile-only"
      type="button"
      aria-label="关闭对话列表"
      @click="sidebarOpen = false"
    />
    <button
      v-if="inspectorOpen"
      class="drawer-backdrop mobile-only"
      type="button"
      aria-label="关闭工具轨迹"
      @click="inspectorOpen = false"
    />

    <div v-if="dialogMode" class="dialog-backdrop" @mousedown.self="closeDialog">
      <form class="dialog" @submit.prevent="confirmDialog">
        <div class="dialog-icon" :class="{ danger: !dialogIsRename }">
          <Pencil v-if="dialogIsRename" :size="20" />
          <AlertTriangle v-else :size="20" />
        </div>
        <button class="icon-button dialog-close" type="button" title="关闭" @click="closeDialog">
          <X :size="18" />
        </button>
        <h2>{{ dialogHeading }}</h2>
        <template v-if="dialogIsRename">
          <label for="conversation-title">{{ dialogIsWorkspace ? '项目名称' : '对话标题' }}</label>
          <input
            id="conversation-title"
            ref="dialogInput"
            v-model="dialogTitle"
            :maxlength="dialogIsWorkspace ? 120 : 200"
            autocomplete="off"
          />
        </template>
        <p v-else-if="dialogIsWorkspace">“{{ dialogTarget?.name }}”仅能在不含对话时删除，托管文件将一并移除。</p>
        <p v-else>“{{ dialogTarget?.title }}”及其全部历史消息将被永久删除。</p>
        <div class="dialog-actions">
          <button class="text-button secondary" type="button" @click="closeDialog">取消</button>
          <button class="text-button" :class="{ danger: !dialogIsRename }" type="submit">
            <Trash2 v-if="!dialogIsRename" :size="16" />
            {{ dialogIsRename ? '保存' : '删除' }}
          </button>
        </div>
      </form>
    </div>

    <div v-if="workspaceDialogOpen" class="dialog-backdrop" @mousedown.self="closeWorkspaceDialog">
      <form class="dialog workspace-dialog" @submit.prevent="createWorkspace">
        <div class="dialog-icon"><FolderGit2 :size="20" /></div>
        <button class="icon-button dialog-close" type="button" title="关闭" @click="closeWorkspaceDialog">
          <X :size="18" />
        </button>
        <h2>创建项目</h2>
        <label for="workspace-name">项目名称</label>
        <input
          id="workspace-name"
          ref="workspaceNameInput"
          v-model="workspaceName"
          maxlength="120"
          autocomplete="off"
          placeholder="例如：课程管理系统"
        />
        <label class="workspace-source-label">初始化方式</label>
        <div class="workspace-mode-switch" role="tablist" aria-label="项目初始化方式">
          <button
            type="button"
            :class="{ active: workspaceMode === 'blank' }"
            role="tab"
            :aria-selected="workspaceMode === 'blank'"
            @click="workspaceMode = 'blank'"
          >
            <MessageSquareText :size="16" />
            空白项目
          </button>
          <button
            type="button"
            :class="{ active: workspaceMode === 'upload' }"
            role="tab"
            :aria-selected="workspaceMode === 'upload'"
            @click="workspaceMode = 'upload'"
          >
            <FolderUp :size="16" />
            上传项目
          </button>
        </div>

        <div v-if="workspaceMode === 'blank'" class="workspace-mode-panel">
          <MessageSquareText :size="18" />
          <p>创建空白项目后，可以在多个对话中持续让 Agent 新建和修改文件。</p>
        </div>

        <div v-else-if="workspaceMode === 'upload'" class="workspace-mode-panel upload-panel">
          <input ref="fileInput" class="visually-hidden" type="file" multiple @change="selectWorkspaceFiles" />
          <input
            ref="folderInput"
            class="visually-hidden"
            type="file"
            multiple
            webkitdirectory
            directory
            @change="selectWorkspaceFiles"
          />
          <div class="upload-actions">
            <button class="text-button secondary" type="button" @click="fileInput?.click()">
              <FileUp :size="16" />
              选择文件
            </button>
            <button class="text-button secondary" type="button" @click="folderInput?.click()">
              <FolderUp :size="16" />
              选择文件夹
            </button>
          </div>
          <p class="selected-files" :title="selectedFilesLabel">{{ selectedFilesLabel }}</p>
        </div>

        <div class="dialog-actions">
          <button class="text-button secondary" type="button" @click="closeWorkspaceDialog">取消</button>
          <button class="text-button" type="submit" :disabled="!canCreateWorkspace">
            {{ registeringWorkspace ? '处理中' : '创建' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
