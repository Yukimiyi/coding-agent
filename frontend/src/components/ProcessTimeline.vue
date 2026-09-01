<script setup>
import {
  BrainCircuit,
  CheckCircle2,
  ChevronDown,
  CircleAlert,
  LoaderCircle,
  ShieldCheck,
  Wrench,
} from 'lucide-vue-next'

defineProps({
  entries: { type: Array, default: () => [] },
})

/** @param {object} entry 运行活动。 @returns {string} 统一后的公开阶段名称。 */
function entryType(entry) {
  return String(entry.type || '').toUpperCase()
}

/** @param {object} entry 运行活动。 @returns {string} 面向用户的公开摘要。 */
function entrySummary(entry) {
  return entry.summary || entry.message || ''
}

/** @param {object} entry 运行活动。 @returns {string} 结果检查的展示状态。 */
function checkState(entry) {
  if (entry.success === true) return 'completed'
  if (entry.success === false) return 'revising'
  return entry.state || 'running'
}
</script>

<template>
  <div v-if="entries.length" class="agent-process">
    <template v-for="entry in entries" :key="entry.id">
      <div v-if="entryType(entry) === 'THOUGHT' || entryType(entry) === 'PROGRESS'" class="process-note thought">
        <BrainCircuit :size="15" />
        <span class="process-kind">思考</span>
        <span class="process-summary">{{ entrySummary(entry) }}</span>
      </div>

      <div
        v-else-if="entryType(entry) === 'RESULT_CHECK' || entryType(entry) === 'REFLECTION'"
        class="process-note result-check"
        :class="checkState(entry)"
      >
        <LoaderCircle v-if="checkState(entry) === 'running'" :size="15" class="spin" />
        <CircleAlert v-else-if="checkState(entry) === 'revising'" :size="15" />
        <ShieldCheck v-else :size="15" />
        <span class="process-kind">结果检查</span>
        <span class="process-summary">{{ entrySummary(entry) }}</span>
      </div>

      <details v-else-if="entryType(entry) === 'ACTION' || entryType(entry) === 'OBSERVATION'" class="process-tool">
        <summary>
          <Wrench v-if="entryType(entry) === 'ACTION'" :size="15" />
          <CircleAlert v-else-if="entry.success === false" :size="15" class="failed" />
          <CheckCircle2 v-else :size="15" />
          <span class="process-kind">{{ entryType(entry) === 'ACTION' ? '行动' : '观察' }}</span>
          <code>{{ entry.toolName || 'unknown_tool' }}</code>
          <span v-if="entryType(entry) === 'OBSERVATION'" class="process-tool-status" :class="{ failed: entry.success === false }">
            {{ entrySummary(entry) }}
          </span>
          <ChevronDown :size="14" class="process-chevron" />
        </summary>
        <pre class="process-detail">{{ entry.detail || '无详细内容' }}</pre>
      </details>
    </template>
  </div>
</template>
