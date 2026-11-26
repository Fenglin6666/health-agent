<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import MessageItem from '../components/MessageItem.vue'
import QuickReplies from '../components/QuickReplies.vue'
import Composer from '../components/Composer.vue'
import { useChat } from '../composables/useChat'
import { useToast } from '../composables/useToast'
import { fetchTimeline, geocode, setLocation, timelineToMessages } from '../api/client'
import type { ChatMessage } from '../types'

const props = defineProps<{
  sessionId: string
  /** 没有会话时由外层创建，创建失败抛异常 */
  ensureSession: () => Promise<string>
}>()
const emit = defineEmits<{ (e: 'sent'): void }>()

const { show } = useToast()
const chat = useChat()
const { messages, loading, error } = chat

const listRef = ref<HTMLElement | null>(null)
const locationInput = ref('')
const locationLabel = ref('')
const locationLoading = ref(false)

/** 正在发送的会话 id：用于区分"我们自己刚建的会话"和"用户切换了会话" */
let sendingSessionId = ''
let suppressReload = false

const EXAMPLES = [
  '我头疼三天了，一直不好',
  '帮我看看这份体检报告',
  '附近有 24 小时药店吗',
]

const quickOptions = computed(() => {
  if (loading.value) return []
  const last = messages.value[messages.value.length - 1]
  return last?.role === 'assistant' ? (last.options ?? []) : []
})

const showTyping = computed(() => {
  if (!loading.value) return false
  const last = messages.value[messages.value.length - 1]
  return !(last?.role === 'assistant' && last.text)
})

/**
 * 滚动节流。
 *
 * <p>之前每个 token 都触发一次 deep watch + smooth scroll：deep watch 要遍历整个消息数组，
 * smooth scroll 每次都会取消上一个动画，长会话里既费 CPU 又追不上内容。
 * 改成 rAF 合并 + 流式期间瞬时滚动，只有一轮结束才用平滑动画。
 */
let scrollScheduled = false

function scheduleScroll(smooth = false): void {
  if (scrollScheduled) return
  scrollScheduled = true
  requestAnimationFrame(() => {
    scrollScheduled = false
    const el = listRef.value
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  })
}

// 只观察"消息条数 + 最后一条文本长度"，不做深遍历
watch(
  () => {
    const last = messages.value[messages.value.length - 1]
    return `${messages.value.length}:${last?.text.length ?? 0}`
  },
  () => scheduleScroll(false),
)

watch(loading, (busy) => {
  if (!busy) scheduleScroll(true)
})

async function loadHistory(id: string): Promise<void> {
  if (!id) return
  try {
    const { turns } = await fetchTimeline(id)
    const restored = timelineToMessages(turns)
    if (restored.length) chat.setMessages(restored)
  } catch {
    // 新会话没有历史，属正常
  }
}

/**
 * 切换会话：先停掉正在生成的请求，再清空重载。
 * 但如果是"首次发送时刚创建的会话"，不能清——那一轮的请求还在飞。
 */
watch(() => props.sessionId, async (id, old) => {
  if (id === old) return
  if (!id) {
    chat.stop()
    chat.clear()
    return
  }
  if (suppressReload || (loading.value && id === sendingSessionId)) return
  chat.stop()
  chat.clear()
  await loadHistory(id)
})

function questionFor(index: number): string {
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') return messages.value[i].text
  }
  return ''
}

async function send(text: string, image?: string): Promise<void> {
  if (loading.value) return
  let sid = props.sessionId
  if (!sid) {
    suppressReload = true
    try {
      sid = await props.ensureSession()
    } catch (e) {
      show(`创建会话失败：${(e as Error).message}`)
      suppressReload = false
      return
    }
  }
  sendingSessionId = sid
  await chat.send(sid, text, image)
  suppressReload = false
  emit('sent')
}

async function onRate(m: ChatMessage, rating: 'up' | 'down' | 'report', reason?: string): Promise<void> {
  const ok = await chat.rate(props.sessionId, m, rating, reason)
  show(ok ? '感谢反馈' : '反馈没提交上，稍后再试')
}

async function doSetLocation(): Promise<void> {
  const addr = locationInput.value.trim()
  if (!addr || locationLoading.value || !props.sessionId) return
  locationLoading.value = true
  try {
    const geo = await geocode(addr)
    if (!geo.found) {
      chat.note(geo.message || '没找到这个地址')
      return
    }
    await setLocation(props.sessionId, geo.lat!, geo.lng!)
    locationLabel.value = geo.formatted_address || addr
    chat.note(`位置已设为 ${locationLabel.value}`)
    locationInput.value = ''
  } catch (e) {
    chat.note(`定位失败：${(e as Error).message}`)
  } finally {
    locationLoading.value = false
  }
}

onMounted(() => loadHistory(props.sessionId))
</script>

<template>
  <main ref="listRef" class="messages">
    <div v-if="!messages.length && !loading" class="empty">
      <p class="empty-title">说说哪里不舒服</p>
      <p class="empty-sub">可以打字，也可以直接粘贴（Ctrl+V）或上传体检报告截图</p>
      <div class="chips">
        <button v-for="q in EXAMPLES" :key="q" class="chip" @click="send(q)">{{ q }}</button>
      </div>
    </div>

    <MessageItem
      v-for="(m, i) in messages"
      :key="m.id"
      :message="m"
      :question="questionFor(i)"
      :can-feedback="m.role === 'assistant' && !!m.turn"
      @rate="(r, reason) => onRate(m, r, reason)"
    />

    <QuickReplies v-if="quickOptions.length" :options="quickOptions" @pick="send" />

    <div v-if="showTyping" class="msg assistant">
      <div class="msg-body">
        <div class="bubble typing"><i></i><i></i><i></i></div>
      </div>
    </div>
  </main>

  <div class="locbar">
    <span class="loc-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11z" /><circle cx="12" cy="10" r="2.6" />
      </svg>
    </span>
    <span v-if="locationLabel" class="loc-set">{{ locationLabel }}</span>
    <input
      v-model="locationInput"
      placeholder="输入位置（如 朝阳区望京），用于查附近药店"
      @keyup.enter="doSetLocation"
    />
    <button class="link" :disabled="locationLoading" @click="doSetLocation">
      {{ locationLoading ? '定位中…' : '定位' }}
    </button>
  </div>

  <Composer
    :loading="loading"
    :error="error"
    @send="send"
    @stop="chat.stop"
    @retry="chat.retry"
  />
</template>
