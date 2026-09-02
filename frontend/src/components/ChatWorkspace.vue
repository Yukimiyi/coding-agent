<script setup>
import { computed, nextTick, ref } from 'vue'
import {
  Bot,
  CheckCircle2,
  ChevronDown,
  Code2,
  Download,
  FileUp,
  FolderUp,
  LoaderCircle,
  Menu,
  MessageSquareText,
  OctagonAlert,
  PanelRight,
  Send,
  ShieldCheck,
  Square,
  SquareTerminal,
  User,
} from 'lucide-vue-next'
import MessageContent from './MessageContent.vue'
import ProcessTimeline from './ProcessTimeline.vue'
import { hasAgentArtifact } from '../artifact.js'

const props = defineProps({
  conversation: { type: Object, default: null },
  messages: { type: Array, default: () => [] },
  loading: Boolean,
  loadingOlder: Boolean,
  hasMore: Boolean,
  busy: Boolean,
  cancelling: Boolean,
  uploading: Boolean,
  uploadResult: { type: Object, default: null },
  downloading: Boolean,
  runStatus: { type: String, default: null },
  currentIteration: { type: Number, default: 0 },
  currentToolName: { type: String, default: '' },
  runActivities: { type: Array, default: () => [] },
  runPlan: { type: Object, default: null },
  latestRun: { type: Object, default: null },
  streamedAnswer: { type: String, default: '' },
})

const emit = defineEmits([
  'menu', 'inspector', 'environment', 'load-older', 'submit', 'cancel',
  'create-chat', 'create-code', 'upload', 'download',
])
const draft = ref('')
const composer = ref(null)
const fileInput = ref(null)
const folderInput = ref(null)
/** 最近一次成功任务是否实际修改过当前项目。 */
const agentArtifactAvailable = computed(() => hasAgentArtifact(props.conversation, props.latestRun))
/** 最近一条助手消息 ID，用于只在最终回答后展示对应执行记录。 */
const latestAssistantId = computed(() => {
  const assistantMessages = props.messages.filter((message) => message.role === 'ASSISTANT')
  return assistantMessages.at(-1)?.id ?? null
})

/** @returns {void} 提交非空任务并重置输入框。 */
function sendTask() {
  const task = draft.value.trim()
  if (!task || props.busy || !props.conversation) return
  emit('submit', task)
  draft.value = ''
  nextTick(resizeComposer)
}

/** @param {KeyboardEvent} event 输入框键盘事件。 @returns {void} */
function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    sendTask()
  }
}

/** @returns {void} 根据内容调整输入框高度。 */
function resizeComposer() {
  if (!composer.value) return
  composer.value.style.height = 'auto'
  composer.value.style.height = `${Math.min(composer.value.scrollHeight, 180)}px`
}

/** @param {Event} event 文件输入事件。 @returns {void} */
function emitFiles(event) {
  const files = Array.from(event.target.files || [])
  if (files.length) emit('upload', files)
  event.target.value = ''
}

/**
 * @param {string|number|Date} value 可由 Date 解析的时间值。
 * @returns {string} 本地小时和分钟。
 */
function formatTime(value) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

/**
 * @param {string|null} status 当前运行状态。
 * @param {number} iteration 当前循环轮次。
 * @param {string} toolName 当前工具名称。
 * @returns {string} Agent 当前运行状态。
 */
function runLabel(status, iteration, toolName) {
  if (status === 'QUEUED') return '等待执行'
  if (toolName) return `第 ${iteration || 1} 轮 · ${toolName}`
  return `第 ${iteration || 1} 轮 · Agent 正在工作`
}

/**
 * @param {object|null} plan 最终结构化计划。
 * @returns {string} 最终计划的简短完成统计。
 */
function planSummary(plan) {
  if (!plan?.steps?.length) return '工作过程'
  const completed = plan.steps.filter((step) => step.status === 'COMPLETED').length
  return `${completed}/${plan.steps.length} 步完成`
}

/** @param {object} reflection 结果检查统计。 @returns {string} 最终公开状态。 */
function resultCheckSummary(reflection) {
  if (!reflection?.rounds) return '任务已完成'
  if (!reflection.revisions) return '结果检查通过'
  return `经 ${reflection.revisions} 次检查修正后完成`
}

/**
 * 将上传字节数格式化为适合紧凑状态栏展示的容量。
 *
 * @param {number} bytes 上传文件总字节数。
 * @returns {string} 以 B、KB 或 MB 表示的容量。
 */
function formatUploadSize(bytes) {
  const size = Number(bytes) || 0
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(size < 10 * 1024 ? 1 : 0)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <main class="chat-workspace">
    <header class="workspace-header">
      <button class="icon-button mobile-only" type="button" title="打开会话" @click="emit('menu')"><Menu :size="19" /></button>
      <div class="workspace-heading">
        <h1>{{ conversation?.title || 'Coding Agent' }}</h1>
        <span v-if="conversation" class="workspace-context">
          <Code2 v-if="conversation.mode === 'CODE'" :size="13" />
          <MessageSquareText v-else :size="13" />
          {{ conversation.mode === 'CODE' ? '编写项目' : '仅聊天' }}
        </span>
      </div>
      <div class="header-actions">
        <button
          v-if="conversation?.mode === 'CODE'"
          class="icon-button"
          type="button"
          title="运行环境"
          @click="emit('environment')"
        ><SquareTerminal :size="18" /></button>
        <button
          v-if="conversation?.mode === 'CODE'"
          class="icon-button"
          type="button"
          :title="conversation.artifactAvailable ? '下载当前项目' : '项目中还没有文件'"
          :disabled="busy || uploading || downloading || !conversation.artifactAvailable"
          @click="emit('download')"
        ><LoaderCircle v-if="downloading" :size="18" class="spin" /><Download v-else :size="18" /></button>
        <button class="icon-button" type="button" title="工具轨迹" @click="emit('inspector')"><PanelRight :size="18" /></button>
      </div>
    </header>

    <section v-if="!conversation" class="mode-empty-state">
      <h2>开始一个新会话</h2>
      <p>聊天模式用于讨论，编程模式自动准备可读写和下载的项目目录。</p>
      <div class="mode-choice-row">
        <button type="button" @click="emit('create-chat')"><MessageSquareText :size="21" /><strong>仅聊天</strong><span>解释、讨论和代码片段</span></button>
        <button type="button" @click="emit('create-code')"><Code2 :size="21" /><strong>编写项目</strong><span>上传、修改、执行和下载</span></button>
      </div>
    </section>

    <section v-else class="message-scroll">
      <button v-if="hasMore" class="load-more" type="button" :disabled="loadingOlder" @click="emit('load-older')">
        {{ loadingOlder ? '加载中' : '加载更早消息' }}
      </button>
      <div v-if="loading" class="message-loading"><span /><span /><span /></div>
      <div v-else-if="messages.length === 0 && !busy" class="empty-chat">
        <Code2 v-if="conversation.mode === 'CODE'" :size="28" />
        <MessageSquareText v-else :size="28" />
        <h2>{{ conversation.mode === 'CODE' ? '描述要完成的编程任务' : '开始讨论' }}</h2>
        <p>{{ conversation.mode === 'CODE' ? '可以先从输入框左下角上传现有项目，也可以直接创建新项目。' : '该模式不会访问或修改任何本地文件。' }}</p>
      </div>

      <div class="message-stream">
        <article v-for="message in messages" :key="message.id" class="message" :class="message.role.toLowerCase()">
          <div class="message-avatar"><User v-if="message.role === 'USER'" :size="16" /><Bot v-else :size="16" /></div>
          <div class="message-body">
            <div class="message-meta"><strong>{{ message.role === 'USER' ? '你' : 'Coding Agent' }}</strong><span>{{ formatTime(message.createdAt) }}</span></div>
            <MessageContent :content="message.content" />
            <details
              v-if="message.role === 'ASSISTANT' && message.id === latestAssistantId && (latestRun?.result?.plan || latestRun?.result?.processTrace?.length) && !busy"
              class="execution-record"
              open
            >
              <summary>
                <ShieldCheck :size="15" />
                <span>工作过程</span>
                <small>{{ planSummary(latestRun.result.plan) }}</small>
                <ChevronDown :size="14" />
              </summary>
              <div class="execution-record-body">
                <section v-if="latestRun.result.plan" class="execution-plan final-plan" aria-label="任务计划">
                  <header><strong>任务计划</strong><span>{{ latestRun.result.plan.goal }}</span></header>
                  <ol>
                    <li v-for="step in latestRun.result.plan.steps" :key="step.id" :class="step.status.toLowerCase()">
                      <CheckCircle2 v-if="step.status === 'COMPLETED'" :size="15" />
                      <OctagonAlert v-else-if="step.status === 'BLOCKED'" :size="15" />
                      <span v-else class="plan-pending-dot" />
                      <span>{{ step.description }}</span>
                    </li>
                  </ol>
                </section>
                <ProcessTimeline :entries="latestRun.result.processTrace || []" />
                <footer>
                  <ShieldCheck :size="14" />
                  <span>{{ resultCheckSummary(latestRun.result.reflection) }}</span>
                </footer>
              </div>
            </details>
          </div>
        </article>

        <article v-if="busy" class="message assistant running-message">
          <div class="message-avatar"><Bot :size="16" /></div>
          <div class="message-body">
            <div class="message-meta"><strong>Coding Agent</strong><span>{{ runLabel(runStatus, currentIteration, currentToolName) }}</span></div>
            <section v-if="runPlan" class="execution-plan" aria-label="任务计划">
              <header><strong>任务计划</strong><span>{{ runPlan.goal }}</span></header>
              <ol>
                <li v-for="step in runPlan.steps" :key="step.id" :class="step.status.toLowerCase()">
                  <CheckCircle2 v-if="step.status === 'COMPLETED'" :size="15" />
                  <LoaderCircle v-else-if="step.status === 'IN_PROGRESS'" :size="15" class="spin" />
                  <OctagonAlert v-else-if="step.status === 'BLOCKED'" :size="15" />
                  <span v-else class="plan-pending-dot" />
                  <span>{{ step.description }}</span>
                </li>
              </ol>
            </section>
            <ProcessTimeline :entries="runActivities" />
            <div v-if="streamedAnswer" class="streamed-answer"><MessageContent :content="streamedAnswer" /></div>
            <div v-else class="thinking-line"><span /><span /><span /></div>
          </div>
        </article>
      </div>
    </section>

    <div v-if="agentArtifactAvailable && !busy" class="artifact-strip">
      <CheckCircle2 :size="16" />
      <span>项目修改结果可下载</span>
      <button type="button" :disabled="downloading" @click="emit('download')"><Download :size="15" />下载修改结果</button>
    </div>

    <footer v-if="conversation" class="composer-region">
      <div v-if="uploading || uploadResult" class="upload-feedback" role="status" aria-live="polite">
        <LoaderCircle v-if="uploading" :size="15" class="spin" />
        <CheckCircle2 v-else :size="15" />
        <span v-if="uploading">正在上传文件</span>
        <span v-else>
          已上传 {{ uploadResult.importedFiles }} 个文件 · {{ formatUploadSize(uploadResult.totalBytes) }}，可以开始描述任务
        </span>
      </div>
      <div class="composer" :class="{ busy }">
        <div v-if="conversation.mode === 'CODE'" class="composer-tools">
          <input ref="fileInput" hidden type="file" multiple @change="emitFiles" />
          <input ref="folderInput" hidden type="file" webkitdirectory directory multiple @change="emitFiles" />
          <button type="button" title="上传文件" :disabled="busy || uploading" @click="fileInput?.click()"><FileUp :size="17" /></button>
          <button type="button" title="上传项目文件夹" :disabled="busy || uploading" @click="folderInput?.click()"><FolderUp :size="17" /></button>
        </div>
        <textarea
          ref="composer"
          v-model="draft"
          rows="1"
          :placeholder="conversation.mode === 'CODE' ? '描述要完成的编程任务' : '输入问题'"
          :disabled="busy || uploading"
          @input="resizeComposer"
          @keydown="handleKeydown"
        />
        <button class="send-button" :class="{ stop: busy }" type="button" :title="busy ? '取消任务' : '发送任务'" :disabled="busy ? cancelling : !draft.trim()" @click="busy ? emit('cancel') : sendTask()">
          <LoaderCircle v-if="cancelling || uploading" :size="18" class="spin" /><Square v-else-if="busy" :size="15" fill="currentColor" /><Send v-else :size="18" />
        </button>
      </div>
      <span class="composer-status"><CheckCircle2 :size="13" />{{ conversation.mode === 'CODE' ? '会话项目目录' : '工具已禁用' }}</span>
    </footer>
  </main>
</template>
