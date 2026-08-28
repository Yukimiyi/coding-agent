<script setup>
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps({
  content: { type: String, default: '' },
})

marked.setOptions({ breaks: true, gfm: true })

const renderedContent = computed(() => DOMPurify.sanitize(marked.parse(props.content)))
</script>

<template>
  <div class="markdown-content" v-html="renderedContent" />
</template>
