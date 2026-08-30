<script setup>
import { computed, ref } from 'vue'
import {
  Code2,
  Download,
  FolderGit2,
  MessageSquare,
  Pencil,
  Plus,
  Search,
  Trash2,
  X,
} from 'lucide-vue-next'

const props = defineProps({
  open: Boolean,
  conversations: { type: Array, default: () => [] },
  workspaces: { type: Array, default: () => [] },
  selectedWorkspaceId: { type: String, default: null },
  selectedId: { type: String, default: null },
  loading: Boolean,
  workspaceLoading: Boolean,
  busy: Boolean,
  online: Boolean,
  downloadingWorkspace: Boolean,
})

defineEmits([
  'close',
  'new',
  'select',
  'rename',
  'delete',
  'workspace-change',
  'add-workspace',
  'download-workspace',
  'rename-workspace',
  'delete-workspace',
])

const query = ref('')

const selectedWorkspace = computed(() =>
  props.workspaces.find((workspace) => workspace.id === props.selectedWorkspaceId),
)

const filteredConversations = computed(() => {
  const normalized = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!normalized) {
    return props.conversations
  }
  return props.conversations.filter((conversation) =>
    conversation.title.toLocaleLowerCase('zh-CN').includes(normalized),
  )
})

const relativeTimeFormatter = new Intl.RelativeTimeFormat('zh-CN', { numeric: 'auto' })

function formatRelativeTime(value) {
  const timestamp = new Date(value).getTime()
  const deltaSeconds = Math.round((timestamp - Date.now()) / 1000)
  const ranges = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  for (const [unit, seconds] of ranges) {
    if (Math.abs(deltaSeconds) >= seconds) {
      return relativeTimeFormatter.format(Math.round(deltaSeconds / seconds), unit)
    }
  }
  return '刚刚'
}
</script>

<template>
  <aside class="conversation-sidebar" :class="{ open }">
    <div class="brand-row">
      <div class="brand-mark"><Code2 :size="20" /></div>
      <div class="brand-copy">
        <strong>Coding Agent</strong>
        <span>Projects</span>
      </div>
      <button class="icon-button sidebar-close mobile-only" type="button" title="关闭" @click="$emit('close')">
        <X :size="18" />
      </button>
    </div>

    <div class="workspace-switcher">
      <FolderGit2 :size="16" />
      <select
        :value="selectedWorkspaceId || ''"
        :disabled="busy || workspaceLoading"
        aria-label="选择项目"
        @change="$emit('workspace-change', $event.target.value || null)"
      >
        <option value="">纯对话</option>
        <option v-for="workspace in workspaces" :key="workspace.id" :value="workspace.id">
          {{ workspace.name }}{{ workspace.type === 'LOCAL' ? '（本地）' : '' }}
        </option>
      </select>
      <button
        class="row-action"
        type="button"
        :title="selectedWorkspace?.type === 'MANAGED' ? '下载项目 ZIP' : '选择受管项目后下载'"
        :disabled="busy || workspaceLoading || downloadingWorkspace || selectedWorkspace?.type !== 'MANAGED'"
        @click="$emit('download-workspace')"
      >
        <Download :size="15" />
      </button>
      <button
        class="row-action"
        type="button"
        title="重命名项目"
        :disabled="busy || workspaceLoading || !selectedWorkspace"
        @click="$emit('rename-workspace', selectedWorkspace)"
      >
        <Pencil :size="14" />
      </button>
      <button
        class="row-action danger"
        type="button"
        title="删除项目"
        :disabled="busy || workspaceLoading || !selectedWorkspace"
        @click="$emit('delete-workspace', selectedWorkspace)"
      >
        <Trash2 :size="14" />
      </button>
      <button
        class="row-action"
        type="button"
        title="创建项目"
        :disabled="busy"
        @click="$emit('add-workspace')"
      >
        <Plus :size="15" />
      </button>
    </div>

    <button class="new-conversation-button" type="button" :disabled="busy" @click="$emit('new')">
      <Plus :size="17" />
      新对话
    </button>

    <label class="conversation-search">
      <Search :size="16" />
      <input v-model="query" type="search" placeholder="搜索对话" aria-label="搜索对话" />
    </label>

    <div class="sidebar-section-label">
      <span>最近对话</span>
      <span>{{ filteredConversations.length }}</span>
    </div>

    <nav class="conversation-list" aria-label="对话列表">
      <div v-if="loading" class="conversation-loading">
        <span v-for="index in 4" :key="index" />
      </div>
      <p v-else-if="filteredConversations.length === 0" class="sidebar-empty">
        {{ query ? '没有匹配的对话' : '暂无对话' }}
      </p>
      <div
        v-for="conversation in filteredConversations"
        :key="conversation.id"
        class="conversation-row"
        :class="{ active: selectedId === conversation.id }"
      >
        <button class="conversation-main" type="button" @click="$emit('select', conversation.id)">
          <MessageSquare :size="16" />
          <span class="conversation-copy">
            <strong>{{ conversation.title }}</strong>
            <small>{{ formatRelativeTime(conversation.updatedAt) }}</small>
          </span>
        </button>
        <div class="conversation-actions">
          <button class="row-action" type="button" title="重命名" @click="$emit('rename', conversation)">
            <Pencil :size="14" />
          </button>
          <button class="row-action danger" type="button" title="删除" @click="$emit('delete', conversation)">
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </nav>

    <div class="sidebar-footer">
      <span class="service-indicator" :class="{ offline: !online }"><span />{{ online ? '后端已连接' : '后端未连接' }}</span>
      <small>DeepSeek · Agent Loop</small>
    </div>
  </aside>
</template>
