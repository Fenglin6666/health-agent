<script setup lang="ts">
defineProps<{
  open: boolean
  digest: string
  timeline: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'consolidate'): void
  (e: 'toggle-timeline'): void
  (e: 'clear'): void
}>()
</script>

<template>
  <div v-if="open" class="drawer-mask" @click.self="emit('close')">
    <aside class="drawer">
      <div class="drawer-head">
        <div>
          <h2>患者档案</h2>
          <p>系统记住的关于你的事</p>
        </div>
        <button class="drawer-close" title="关闭" @click="emit('close')">×</button>
      </div>

      <div class="drawer-body">
        <pre v-if="digest" class="digest-text">{{ digest }}</pre>
        <p v-else class="digest-empty">
          还没有档案。多聊几句，或者点下面「整理记忆」从这段对话里提取。
        </p>

        <div class="drawer-actions">
          <button class="ghost" @click="emit('consolidate')">整理记忆</button>
          <button class="ghost" @click="emit('toggle-timeline')">
            {{ timeline ? '收起状态流' : '状态流' }}
          </button>
          <button class="ghost danger" @click="emit('clear')">清空</button>
        </div>

        <pre v-if="timeline" class="timeline">{{ timeline }}</pre>
      </div>
    </aside>
  </div>
</template>
