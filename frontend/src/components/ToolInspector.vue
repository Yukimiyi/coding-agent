<script setup>
import { computed, ref, watch } from 'vue'
import {
  Activity,
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  Clipboard,
  Clock3,
  LoaderCircle,
  X,
  XCircle,
} from 'lucide-vue-next'

const props = defineProps({
  open: Boolean,
  steps: { type: Array, default: () => [] },
  runStatus: { type: String, default: null },
  currentIteration: { type: Number, default: 0 },
  currentToolName: { type: String, default: '' },
})

defineEmits(['close'])

const expandedSteps = ref(new Set())
const copiedStep = ref(null)

watch(
  () => props.steps,
  () => {
    expandedSteps.value = new Set()
  },
)

const outcomeCounts = computed(() => {
  const counts = { success: 0, warning: 0, failure: 0 }
  props.steps.forEach((step) => counts[stepOutcome(step)]++)
  return counts
})

/**
 * 尝试解析工具协议中的 JSON 字符串。
 *
 * @param {string|null} value 待解析文本。
 * @returns {unknown|null} JSON 值；空值或解析失败时返回 `null`。
 */
function parseJson(value) {
  if (!value) {
    return null
  }
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

/**
 * 根据工具结果及命令退出状态划分轨迹结果。
 *
 * @param {object} step 工具执行步骤。
 * @returns {'success'|'warning'|'failure'} 轨迹展示级别。
 */
function stepOutcome(step) {
  if (!step.success) {
    return 'failure'
  }
  if (step.toolName === 'execute_command') {
    const result = parseJson(step.content)
    if (result?.timedOut || (typeof result?.exitCode === 'number' && result.exitCode !== 0)) {
      return 'warning'
    }
  }
  return 'success'
}

/**
 * 生成人工可读的工具执行结果标签。
 *
 * @param {object} step 工具执行步骤。
 * @returns {string} 成功、超时、退出码或调用失败文本。
 */
function outcomeLabel(step) {
  const outcome = stepOutcome(step)
  if (outcome === 'failure') {
    return '调用失败'
  }
  if (outcome === 'warning') {
    const result = parseJson(step.content)
    return result?.timedOut ? '执行超时' : `退出码 ${result?.exitCode}`
  }
  return '成功'
}

/**
 * 将工具参数或结果格式化为便于检查的文本。
 *
 * @param {string|null} value JSON 字符串或普通文本。
 * @returns {string} 缩进 JSON、原始文本或空值占位符。
 */
function formatPayload(value) {
  const parsed = parseJson(value)
  return parsed ? JSON.stringify(parsed, null, 2) : value || '无'
}

/**
 * 切换指定工具步骤的展开状态。
 *
 * @param {number} index 工具步骤在列表中的索引。
 * @returns {void}
 */
function toggleStep(index) {
  const next = new Set(expandedSteps.value)
  if (next.has(index)) {
    next.delete(index)
  } else {
    next.add(index)
  }
  expandedSteps.value = next
}

/**
 * 将完整工具调用轨迹复制到剪贴板，并短暂显示成功状态。
 *
 * @param {object} step 工具执行步骤。
 * @param {number} index 工具步骤在列表中的索引。
 * @returns {Promise<void>} 剪贴板写入完成后结束。
 * @throws {DOMException} 浏览器拒绝剪贴板权限时抛出。
 */
async function copyStep(step, index) {
  const text = `工具：${step.toolName}\n参数：\n${formatPayload(step.arguments)}\n\n结果：\n${formatPayload(step.content)}`
  await navigator.clipboard.writeText(text)
  copiedStep.value = index
  window.setTimeout(() => {
    if (copiedStep.value === index) {
      copiedStep.value = null
    }
  }, 1500)
}
</script>

<template>
  <aside class="tool-inspector" :class="{ open }">
    <header class="inspector-header">
      <div>
        <Activity :size="18" />
        <h2>工具轨迹</h2>
      </div>
      <button class="icon-button" type="button" title="关闭轨迹面板" @click="$emit('close')">
        <X :size="18" />
      </button>
    </header>

    <div v-if="runStatus === 'QUEUED' || runStatus === 'RUNNING'" class="live-run-strip">
      <LoaderCircle :size="15" class="spin" />
      <span>
        {{ runStatus === 'QUEUED' ? '等待执行' : `第 ${currentIteration || 1} 轮` }}
        <small v-if="currentToolName">{{ currentToolName }}</small>
      </span>
    </div>

    <div class="trace-summary">
      <div><strong>{{ steps.length }}</strong><span>调用</span></div>
      <div><strong>{{ outcomeCounts.success }}</strong><span>成功</span></div>
      <div :class="{ emphasized: outcomeCounts.warning + outcomeCounts.failure > 0 }">
        <strong>{{ outcomeCounts.warning + outcomeCounts.failure }}</strong><span>异常</span>
      </div>
    </div>

    <div v-if="steps.length === 0" class="inspector-empty">
      <Activity :size="25" />
      <p>本轮尚无工具记录</p>
    </div>

    <div v-else class="trace-list">
      <article
        v-for="(step, index) in steps"
        :key="step.toolCallId || index"
        class="trace-step"
        :class="stepOutcome(step)"
      >
        <button class="trace-heading" type="button" @click="toggleStep(index)">
          <span class="trace-state-icon">
            <CheckCircle2 v-if="stepOutcome(step) === 'success'" :size="16" />
            <AlertTriangle v-else-if="stepOutcome(step) === 'warning'" :size="16" />
            <XCircle v-else :size="16" />
          </span>
          <span class="trace-name">
            <strong>{{ step.toolName }}</strong>
            <small>第 {{ step.iteration }} 轮 · {{ outcomeLabel(step) }}</small>
          </span>
          <ChevronDown :size="16" :class="{ rotated: expandedSteps.has(index) }" />
        </button>

        <div v-if="expandedSteps.has(index)" class="trace-detail">
          <div class="detail-label">
            <span>参数</span>
            <button type="button" :title="copiedStep === index ? '已复制' : '复制轨迹'" @click="copyStep(step, index)">
              <Check v-if="copiedStep === index" :size="14" />
              <Clipboard v-else :size="14" />
            </button>
          </div>
          <pre>{{ formatPayload(step.arguments) }}</pre>
          <div class="detail-label"><span>结果</span></div>
          <pre>{{ formatPayload(step.content) }}</pre>
          <div v-if="step.error" class="trace-error">
            <Clock3 :size="14" />
            <span>{{ step.error.code }}：{{ step.error.message }}</span>
          </div>
        </div>
      </article>
    </div>
  </aside>
</template>
