<script setup>
import { computed, ref } from 'vue'
import { Code2, MessageSquare, MessageSquareText, Pencil, Search, Trash2, X } from 'lucide-vue-next'

const props = defineProps({
  open: Boolean,
  conversations: { type: Array, default: () => [] },
  selectedId: { type: String, default: null },
  loading: Boolean,
  busy: Boolean,
  online: Boolean,
})

defineEmits(['close', 'new-chat', 'new-code', 'select', 'rename', 'delete'])

const query = ref('')
const filteredConversations = computed(() => {
  const normalized = query.value.trim().toLocaleLowerCase('zh-CN')
  return normalized
    ? props.conversations.filter((item) => item.title.toLocaleLowerCase('zh-CN').includes(normalized))
    : props.conversations
})
const relativeTimeFormatter = new Intl.RelativeTimeFormat('zh-CN', { numeric: 'auto' })

/** @returns {string} 中文相对时间。 */
function formatRelativeTime(value) {
  const deltaSeconds = Math.round((new Date(value).getTime() - Date.now()) / 1000)
  const ranges = [['year', 31_536_000], ['month', 2_592_000], ['day', 86_400], ['hour', 3_600], ['minute', 60]]
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
      <div class="brand-copy"><strong>Coding Agent</strong><span>Agent Workspace</span></div>
      <button class="icon-button sidebar-close mobile-only" type="button" title="关闭" @click="$emit('close')">
        <X :size="18" />
      </button>
    </div>

    <div class="new-conversation-actions">
      <button type="button" :disabled="busy" @click="$emit('new-chat')">
        <MessageSquareText :size="16" />仅聊天
      </button>
      <button type="button" :disabled="busy" @click="$emit('new-code')">
        <Code2 :size="16" />编写项目
      </button>
    </div>

    <label class="conversation-search">
      <Search :size="16" />
      <input v-model="query" type="search" placeholder="搜索会话" aria-label="搜索会话" />
    </label>

    <div class="sidebar-section-label"><span>最近会话</span><span>{{ filteredConversations.length }}</span></div>
    <nav class="conversation-list" aria-label="会话列表">
      <div v-if="loading" class="conversation-loading"><span v-for="index in 4" :key="index" /></div>
      <p v-else-if="filteredConversations.length === 0" class="sidebar-empty">
        {{ query ? '没有匹配的会话' : '暂无会话' }}
      </p>
      <div
        v-for="conversation in filteredConversations"
        :key="conversation.id"
        class="conversation-row"
        :class="{ active: selectedId === conversation.id }"
      >
        <button class="conversation-main" type="button" @click="$emit('select', conversation.id)">
          <Code2 v-if="conversation.mode === 'CODE'" :size="16" />
          <MessageSquare v-else :size="16" />
          <span class="conversation-copy">
            <strong>{{ conversation.title }}</strong>
            <small>{{ conversation.mode === 'CODE' ? '项目' : '聊天' }} · {{ formatRelativeTime(conversation.updatedAt) }}</small>
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
