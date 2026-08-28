<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  AlertTriangle,
  Menu,
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
const selectedConversationId = ref(null)
const messages = ref([])
const toolSteps = ref([])
const nextCursor = ref(null)
const hasMoreMessages = ref(false)
const loadingConversations = ref(true)
const loadingMessages = ref(false)
const loadingOlder = ref(false)
const busy = ref(false)
const online = ref(false)
const errorMessage = ref('')
const sidebarOpen = ref(false)
const inspectorOpen = ref(typeof window === 'undefined' || window.innerWidth > 900)
const dialogMode = ref(null)
const dialogTarget = ref(null)
const dialogTitle = ref('')
const dialogInput = ref(null)

let loadSequence = 0

const activeConversation = computed(() =>
  conversations.value.find((conversation) => conversation.id === selectedConversationId.value),
)

const workspaceTitle = computed(() => activeConversation.value?.title || '新对话')

onMounted(async () => {
  await Promise.all([checkHealth(), refreshConversations()])
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].id)
  }
})

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
    conversations.value = await agentApi.listConversations()
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loadingConversations.value = false
  }
}

async function selectConversation(conversationId) {
  if (busy.value || conversationId === selectedConversationId.value) {
    sidebarOpen.value = false
    return
  }
  selectedConversationId.value = conversationId
  messages.value = []
  toolSteps.value = []
  errorMessage.value = ''
  sidebarOpen.value = false
  await loadMessages(conversationId)
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
  errorMessage.value = ''
  toolSteps.value = []

  try {
    const response = await agentApi.chat(requestedConversationId, normalizedTask)
    selectedConversationId.value = response.conversationId
    toolSteps.value = response.result.toolSteps || []
    if (!response.result.completed) {
      errorMessage.value = `Agent 已停止：${response.result.stopReason}`
    }
    await refreshConversations()
    await loadMessages(response.conversationId)
  } catch (error) {
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
  } finally {
    busy.value = false
  }
}

function openRenameDialog(conversation) {
  dialogMode.value = 'rename'
  dialogTarget.value = conversation
  dialogTitle.value = conversation.title
  nextTick(() => dialogInput.value?.select())
}

function openDeleteDialog(conversation) {
  dialogMode.value = 'delete'
  dialogTarget.value = conversation
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
    if (dialogMode.value === 'rename') {
      const title = dialogTitle.value.trim()
      if (!title) {
        return
      }
      await agentApi.renameConversation(target.id, title)
    } else {
      await agentApi.deleteConversation(target.id)
      if (selectedConversationId.value === target.id) {
        startNewConversation()
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
      :online="online"
      @close="sidebarOpen = false"
      @new="startNewConversation"
      @select="selectConversation"
      @rename="openRenameDialog"
      @delete="openDeleteDialog"
    />

    <section class="workspace-column">
      <header class="workspace-header">
        <button class="icon-button mobile-only" type="button" title="打开对话列表" @click="sidebarOpen = true">
          <Menu :size="19" />
        </button>
        <div class="workspace-heading">
          <h1>{{ workspaceTitle }}</h1>
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
        @send="submitTask"
        @load-older="loadOlderMessages"
        @dismiss-error="errorMessage = ''"
      />
    </section>

    <ToolInspector :open="inspectorOpen" :steps="toolSteps" @close="inspectorOpen = false" />

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
        <div class="dialog-icon" :class="{ danger: dialogMode === 'delete' }">
          <Pencil v-if="dialogMode === 'rename'" :size="20" />
          <AlertTriangle v-else :size="20" />
        </div>
        <button class="icon-button dialog-close" type="button" title="关闭" @click="closeDialog">
          <X :size="18" />
        </button>
        <h2>{{ dialogMode === 'rename' ? '重命名对话' : '删除对话' }}</h2>
        <template v-if="dialogMode === 'rename'">
          <label for="conversation-title">对话标题</label>
          <input
            id="conversation-title"
            ref="dialogInput"
            v-model="dialogTitle"
            maxlength="200"
            autocomplete="off"
          />
        </template>
        <p v-else>“{{ dialogTarget?.title }}”及其全部历史消息将被永久删除。</p>
        <div class="dialog-actions">
          <button class="text-button secondary" type="button" @click="closeDialog">取消</button>
          <button class="text-button" :class="{ danger: dialogMode === 'delete' }" type="submit">
            <Trash2 v-if="dialogMode === 'delete'" :size="16" />
            {{ dialogMode === 'rename' ? '保存' : '删除' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
