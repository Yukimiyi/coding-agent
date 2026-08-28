<script setup>
import { nextTick, ref } from 'vue'
import {
  AlertCircle,
  ArrowDown,
  Bot,
  CheckCircle2,
  CornerDownLeft,
  LoaderCircle,
  Send,
  TerminalSquare,
  UserRound,
  X,
} from 'lucide-vue-next'
import MessageContent from './MessageContent.vue'

defineProps({
  messages: { type: Array, default: () => [] },
  busy: Boolean,
  loading: Boolean,
  loadingOlder: Boolean,
  hasMore: Boolean,
  errorMessage: { type: String, default: '' },
})

const emit = defineEmits(['send', 'load-older', 'dismiss-error'])
const draft = ref('')
const composer = ref(null)

const taskSuggestions = [
  '检查当前项目并运行测试',
  '定位并修复现有编译错误',
  '梳理项目结构并给出改进建议',
]

function sendTask(task = draft.value) {
  const normalized = task.trim()
  if (!normalized) {
    return
  }
  emit('send', normalized)
  draft.value = ''
  nextTick(resizeComposer)
}

function handleKeydown(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    sendTask()
  }
}

function resizeComposer() {
  if (!composer.value) {
    return
  }
  composer.value.style.height = 'auto'
  composer.value.style.height = `${Math.min(composer.value.scrollHeight, 180)}px`
}

function formatTime(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <main class="chat-workspace">
    <div v-if="errorMessage" class="error-banner" role="alert">
      <AlertCircle :size="17" />
      <span>{{ errorMessage }}</span>
      <button type="button" title="关闭" @click="emit('dismiss-error')"><X :size="16" /></button>
    </div>

    <section class="message-scroll" aria-live="polite">
      <div v-if="loading" class="message-loading">
        <LoaderCircle :size="22" class="spin" />
      </div>

      <div v-else-if="messages.length === 0" class="empty-chat">
        <div class="empty-symbol"><TerminalSquare :size="30" /></div>
        <h2>新对话</h2>
        <div class="suggestion-list">
          <button
            v-for="suggestion in taskSuggestions"
            :key="suggestion"
            type="button"
            :disabled="busy"
            @click="sendTask(suggestion)"
          >
            <CornerDownLeft :size="15" />
            {{ suggestion }}
          </button>
        </div>
      </div>

      <div v-else class="message-stream">
        <button
          v-if="hasMore"
          class="load-older-button"
          type="button"
          :disabled="loadingOlder"
          @click="emit('load-older')"
        >
          <LoaderCircle v-if="loadingOlder" :size="15" class="spin" />
          <ArrowDown v-else :size="15" />
          加载更早消息
        </button>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message"
          :class="[message.role.toLowerCase(), { failed: message.status === 'ERROR' }]"
        >
          <div class="message-avatar">
            <UserRound v-if="message.role === 'USER'" :size="17" />
            <Bot v-else :size="18" />
          </div>
          <div class="message-body">
            <div class="message-meta">
              <strong>{{ message.role === 'USER' ? '你' : 'Coding Agent' }}</strong>
              <span>{{ formatTime(message.createdAt) }}</span>
              <AlertCircle v-if="message.status === 'ERROR'" :size="14" />
            </div>
            <p v-if="message.role === 'USER'" class="user-content">{{ message.content }}</p>
            <MessageContent v-else :content="message.content" />
          </div>
        </article>

        <article v-if="busy" class="message assistant pending-message">
          <div class="message-avatar"><Bot :size="18" /></div>
          <div class="message-body">
            <div class="message-meta"><strong>Coding Agent</strong><span>执行中</span></div>
            <div class="thinking-line"><span /><span /><span /></div>
          </div>
        </article>
      </div>
    </section>

    <footer class="composer-region">
      <div class="composer" :class="{ busy }">
        <textarea
          ref="composer"
          v-model="draft"
          rows="1"
          placeholder="向 Coding Agent 提交任务"
          :disabled="busy"
          @input="resizeComposer"
          @keydown="handleKeydown"
        />
        <button
          class="send-button"
          type="button"
          title="发送任务"
          :disabled="busy || !draft.trim()"
          @click="sendTask()"
        >
          <LoaderCircle v-if="busy" :size="18" class="spin" />
          <Send v-else :size="18" />
        </button>
      </div>
      <span class="composer-status">
        <CheckCircle2 :size="13" /> 工作区内受控执行
      </span>
    </footer>
  </main>
</template>
