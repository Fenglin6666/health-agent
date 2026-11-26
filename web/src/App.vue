<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppHeader from './components/AppHeader.vue'
import ChatView from './views/ChatView.vue'
import SessionSidebar from './components/SessionSidebar.vue'
import ProfileDrawer from './components/ProfileDrawer.vue'
import Toast from './components/Toast.vue'
import { useSessions } from './composables/useSessions'
import { useProfile } from './composables/useProfile'
import { useToast } from './composables/useToast'
import { fetchRoutingStats } from './api/client'

const sessionsApi = useSessions()
const { sessions, currentId } = sessionsApi
const { digest, timeline, consolidateMemory, toggleTimeline, clear, resetTimeline } = useProfile()
const { show } = useToast()

const sidebarOpen = ref(false)
const drawerOpen = ref(false)
const stats = ref<Record<string, number>>({})

onMounted(async () => {
  try {
    await sessionsApi.restore()
    stats.value = await fetchRoutingStats()
  } catch (e) {
    show(`无法连接后端：${(e as Error).message}`)
  }
})

function newChat(): void {
  // 不建会话：等用户真的发出第一条消息再建，否则历史里会多一行空记录
  sessionsApi.reset()
  sidebarOpen.value = false
  resetTimeline()
}

async function selectSession(id: string): Promise<void> {
  await sessionsApi.switchTo(id)
  sidebarOpen.value = false
  resetTimeline()
}

async function ensureSession(): Promise<string> {
  return sessionsApi.ensure()
}

async function onSent(): Promise<void> {
  // 发完一条才刷新列表：新会话在第一条消息落库前是被过滤掉的
  stats.value = await fetchRoutingStats()
  await sessionsApi.load()
}
</script>

<template>
  <div class="app">
    <AppHeader
      :stats="stats"
      :drawer-open="drawerOpen"
      @sidebar="sidebarOpen = true"
      @drawer="drawerOpen = !drawerOpen"
    />

    <ChatView
      :session-id="currentId"
      :ensure-session="ensureSession"
      @sent="onSent"
    />

    <p class="disclaimer">非医疗器械，不提供诊疗服务。如有不适请及时就医。</p>

    <SessionSidebar
      :sessions="sessions"
      :current-id="currentId"
      :open="sidebarOpen"
      @new="newChat"
      @select="selectSession"
      @close="sidebarOpen = false"
    />

    <ProfileDrawer
      :open="drawerOpen"
      :digest="digest"
      :timeline="timeline"
      @close="drawerOpen = false"
      @consolidate="consolidateMemory(currentId)"
      @toggle-timeline="toggleTimeline(currentId)"
      @clear="clear"
    />

    <Toast />
  </div>
</template>
