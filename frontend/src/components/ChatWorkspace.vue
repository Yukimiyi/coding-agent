<script setup>
import { nextTick, ref } from 'vue'
import {
  AlertCircle,
  ArrowDown,
  Bot,
  BrainCircuit,
  CheckCircle2,
  ChevronDown,
  CornerDownLeft,
  Eye,
  LoaderCircle,
  Send,
  Square,
  TerminalSquare,
  UserRound,
  Wrench,
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
  runStatus: { type: String, default: null },
  currentIteration: { type: Number, default: 0 },
  currentToolName: { type: String, default: '' },
  activities: { type: Array, default: () => [] },
  streamedAnswer: { type: String, default: '' },
  cancelling: Boolean,
  workspaceActive: Boolean,
})

const emit = defineEmits(['send', 'cancel', 'load-older', 'dismiss-error'])
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

function runLabel(status, iteration, toolName) {
  if (status === 'QUEUED') {
    return '等待执行'
  }
  if (toolName) {
    return `第 ${iteration} 轮 · ${toolName}`
  }
  return iteration > 0 ? `第 ${iteration} 轮 · 模型处理中` : '准备执行'
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
            <div class="message-meta">
              <strong>Coding Agent</strong>
              <span>{{ cancelling ? '正在取消' : runLabel(runStatus, currentIteration, currentToolName) }}</span>
            </div>
            <div v-if="activities.length" class="agent-process">
              <template v-for="activity in activities" :key="activity.id">
                <div
                  v-if="activity.type === 'perception' || activity.type === 'thought'"
                  class="process-note"
                  :class="activity.type"
                >
                  <Eye v-if="activity.type === 'perception'" :size="15" />
                  <BrainCircuit v-else :size="15" />
                  <span class="process-kind">{{ activity.type === 'perception' ? '感知' : '思考摘要' }}</span>
                  <span class="process-summary">{{ activity.message }}</span>
                </div>
                <details v-else class="process-tool">
                  <summary>
                    <Wrench v-if="activity.type === 'action'" :size="15" />
                    <Eye v-else :size="15" />
                    <span class="process-kind">{{ activity.type === 'action' ? '行动' : '观察' }}</span>
                    <code>{{ activity.toolName }}</code>
                    <span
                      v-if="activity.type === 'observation'"
                      class="process-result"
                      :class="{ failed: !activity.success }"
                    >
                      {{ activity.success ? '成功' : '失败' }}
                    </span>
                    <ChevronDown :size="14" class="process-chevron" />
                  </summary>
                  <pre class="process-detail">{{ activity.detail || '无返回内容' }}</pre>
                </details>
              </template>
            </div>
            <div v-if="streamedAnswer" class="streamed-answer">
              <MessageContent :content="streamedAnswer" />
            </div>
            <div v-if="!streamedAnswer" class="thinking-line"><span /><span /><span /></div>
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
          :class="{ stop: busy }"
          type="button"
          :title="busy ? '取消任务' : '发送任务'"
          :disabled="busy ? cancelling : !draft.trim()"
          @click="busy ? emit('cancel') : sendTask()"
        >
          <LoaderCircle v-if="cancelling" :size="18" class="spin" />
          <Square v-else-if="busy" :size="15" fill="currentColor" />
          <Send v-else :size="18" />
        </button>
      </div>
      <span class="composer-status">
        <CheckCircle2 :size="13" /> {{ workspaceActive ? '本地工作区' : '纯对话模式' }}
      </span>
    </footer>
  </main>
</template>
