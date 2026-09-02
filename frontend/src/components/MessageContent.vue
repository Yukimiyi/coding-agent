<script setup>
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps({
  content: { type: String, default: '' },
})

marked.setOptions({ breaks: true, gfm: true })

/** 将 Markdown 转为 HTML，并在注入页面前移除不可信标签和属性。 */
const renderedContent = computed(() => DOMPurify.sanitize(marked.parse(props.content)))
</script>

<template>
  <div class="markdown-content" v-html="renderedContent" />
</template>
