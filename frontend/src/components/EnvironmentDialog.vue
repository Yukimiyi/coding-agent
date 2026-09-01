<script setup>
import { computed } from 'vue'
import { CircleCheck, CircleX, RefreshCw, SquareTerminal, X } from 'lucide-vue-next'

const props = defineProps({
  snapshot: {
    type: Object,
    default: null,
  },
  loading: Boolean,
  workspaceName: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['close', 'refresh'])

const availableCount = computed(() =>
  props.snapshot?.tools?.filter((tool) => tool.available).length || 0,
)

const sourceLabels = {
  CONFIGURED_PATH: '配置目录',
  SYSTEM_PATH: '系统 PATH',
  PROJECT_WRAPPER: '项目 Wrapper',
  UNAVAILABLE: '不可用',
}

/**
 * 将后端环境来源枚举转换为中文标签。
 *
 * @param {string|null} source 环境工具来源代码。
 * @returns {string} 可展示的来源名称。
 */
function sourceLabel(source) {
  return sourceLabels[source] || source || '未知来源'
}

/**
 * 格式化环境探测时间。
 *
 * @param {string|number|Date|null} value 可由 {@link Date} 解析的时间值。
 * @returns {string} 本地时分秒或未检测提示。
 */
function formatCheckedAt(value) {
  if (!value) {
    return '尚未检测'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <div class="dialog-backdrop" @mousedown.self="emit('close')">
    <section class="dialog environment-dialog" role="dialog" aria-modal="true" aria-labelledby="environment-title">
      <div class="dialog-icon"><SquareTerminal :size="20" /></div>
      <button class="icon-button dialog-close" type="button" title="关闭" @click="emit('close')">
        <X :size="18" />
      </button>

      <div class="environment-heading">
        <div>
          <h2 id="environment-title">运行环境</h2>
          <p>{{ workspaceName || '宿主环境' }}</p>
        </div>
        <span v-if="snapshot" class="environment-count">
          {{ availableCount }}/{{ snapshot.tools.length }} 可用
        </span>
      </div>

      <div v-if="loading && !snapshot" class="environment-loading">
        <RefreshCw :size="18" class="spin" />
        正在检测开发工具
      </div>

      <div v-else-if="snapshot" class="environment-list">
        <div v-for="tool in snapshot.tools" :key="tool.id" class="environment-row">
          <CircleCheck v-if="tool.available" :size="18" class="environment-ok" />
          <CircleX v-else :size="18" class="environment-missing" />
          <div class="environment-tool">
            <div class="environment-tool-title">
              <strong>{{ tool.name }}</strong>
              <code v-if="tool.command">{{ tool.command }}</code>
            </div>
            <span>{{ tool.version || tool.message }}</span>
            <small v-if="!tool.available && tool.installHint">{{ tool.installHint }}</small>
          </div>
          <span class="environment-source" :class="{ available: tool.available }">
            {{ sourceLabel(tool.source) }}
          </span>
        </div>
      </div>

      <div class="environment-footer">
        <span>检测于 {{ formatCheckedAt(snapshot?.checkedAt) }}，安装新工具后请重启应用。</span>
        <button class="text-button secondary" type="button" :disabled="loading" @click="emit('refresh')">
          <RefreshCw :size="16" :class="{ spin: loading }" />
          重新检测
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.environment-dialog {
  width: min(680px, 100%);
  max-height: min(760px, calc(100vh - 32px));
  display: flex;
  flex-direction: column;
}

.environment-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin: 12px 0 14px;
}

.environment-heading h2 {
  margin: 0 0 2px;
}

.environment-heading p {
  color: var(--muted);
}

.environment-count {
  flex: 0 0 auto;
  color: var(--ink-soft);
  font-size: 12px;
  font-weight: 650;
}

.environment-list {
  overflow: auto;
  border-block: 1px solid var(--border);
}

.environment-row {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  padding: 11px 2px;
  border-bottom: 1px solid var(--border);
}

.environment-row:last-child {
  border-bottom: 0;
}

.environment-ok {
  color: var(--accent);
}

.environment-missing {
  color: var(--danger);
}

.environment-tool {
  min-width: 0;
}

.environment-tool-title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.environment-tool-title strong {
  font-size: 13px;
}

.environment-tool-title code {
  color: var(--accent-strong);
  font-size: 11px;
}

.environment-tool > span,
.environment-tool small {
  display: block;
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.45;
}

.environment-tool small {
  color: var(--ink-soft);
}

.environment-source {
  color: var(--muted);
  font-size: 10px;
  white-space: nowrap;
}

.environment-source.available {
  color: var(--accent-strong);
}

.environment-loading {
  display: flex;
  min-height: 240px;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: var(--muted);
  font-size: 12px;
}

.environment-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 14px;
}

.environment-footer > span {
  color: var(--muted);
  font-size: 10px;
}

.spin {
  animation: environment-spin 0.9s linear infinite;
}

@keyframes environment-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 600px) {
  .environment-dialog {
    max-height: calc(100vh - 20px);
  }

  .environment-row {
    grid-template-columns: 20px minmax(0, 1fr);
  }

  .environment-source {
    grid-column: 2;
  }

  .environment-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
