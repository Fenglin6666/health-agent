<script setup lang="ts">
defineProps<{
  stats: Record<string, number>
  drawerOpen: boolean
}>()

const emit = defineEmits<{
  (e: 'sidebar'): void
  (e: 'drawer'): void
}>()
</script>

<template>
  <header>
    <button class="icon-btn menu-btn" title="会话列表" @click="emit('sidebar')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 6h16M4 12h16M4 18h16" />
      </svg>
    </button>

    <div class="brand">
      <span class="mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 12h4l2-5 3 10 2.5-5H21" />
        </svg>
      </span>
      <div class="brand-text">
        <h1>健康管家</h1>
        <p>症状 · 用药 · 报告 · 就医</p>
      </div>
    </div>

    <span class="stats" title="被挡在模型外的请求 / 本轮 agent 转接次数">
      拦截 {{ (stats.off_topic ?? 0) + (stats.greeting ?? 0) + (stats.redflag ?? 0) }}
      · 转接 {{ stats.handoff ?? 0 }}
    </span>

    <button class="profile-btn" :class="{ on: drawerOpen }" title="患者档案与状态流" @click="emit('drawer')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="8" r="3.6" />
        <path d="M4.5 20a7.5 7.5 0 0 1 15 0" />
      </svg>
    </button>
  </header>
</template>
