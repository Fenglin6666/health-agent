<script setup lang="ts">
import type { SessionSummary } from '../types'

defineProps<{
  sessions: SessionSummary[]
  currentId: string
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'new'): void
  (e: 'select', id: string): void
  (e: 'close'): void
}>()

function when(s: SessionSummary): string {
  if (!s.updatedAt) return ''
  // 后端给的是 ISO UTC（...Z），必须转本地时区再显示，
  // 否则会出现"15:22 建的会话显示成 07:22"。
  const d = new Date(s.updatedAt)
  if (Number.isNaN(d.getTime())) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const today = new Date().toDateString() === d.toDateString()
  return today ? `${hh}:${mm}` : `${d.getMonth() + 1}-${d.getDate()} ${hh}:${mm}`
}
</script>

<template>
  <div v-if="open" class="side-mask" @click.self="emit('close')">
    <aside class="sidebar">
      <div class="sidebar-head">
        <h2>会话</h2>
        <button class="sidebar-close" title="关闭" @click="emit('close')">×</button>
      </div>

      <button class="new-chat" @click="emit('new')">＋ 新对话</button>

      <p v-if="!sessions.length" class="sidebar-empty">还没有历史会话</p>
      <ul v-else class="session-list">
        <li
          v-for="s in sessions"
          :key="s.id"
          :class="{ active: s.id === currentId }"
          @click="emit('select', s.id)"
        >
          <p class="session-preview">{{ s.preview || '（无标题）' }}</p>
          <p class="session-meta">{{ s.turns }} 轮 · {{ when(s) }}</p>
        </li>
      </ul>
    </aside>
  </div>
</template>
