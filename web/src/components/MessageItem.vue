<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { agentLabel } from '../agents'
import { copyText } from '../utils/clipboard'
import { shareAsImage } from '../utils/share'
import { canSpeak, speak, stopSpeaking } from '../utils/speech'
import { useToast } from '../composables/useToast'
import type { ChatMessage } from '../types'

const props = defineProps<{
  message: ChatMessage
  /** 这条回答对应的问题，分享图要用 */
  question?: string
  canFeedback?: boolean
}>()

const emit = defineEmits<{
  (e: 'rate', rating: 'up' | 'down' | 'report', reason?: string): void
}>()

const { show } = useToast()

const downOpen = ref(false)
const menu = ref<{ x: number; y: number } | null>(null)
const speaking = ref(false)
let pressTimer: number | undefined

const isAssistant = computed(() => props.message.role === 'assistant')
const reasons = ['不准确', '不安全', '太啰嗦', '其他']

function clearPress(): void {
  if (pressTimer) {
    window.clearTimeout(pressTimer)
    pressTimer = undefined
  }
}

function startPress(e: PointerEvent): void {
  if ((e.target as HTMLElement).closest('button')) return
  clearPress()
  const x = e.clientX
  const y = e.clientY
  pressTimer = window.setTimeout(() => openMenu({ clientX: x, clientY: y } as MouseEvent), 500)
}

function openMenu(e: MouseEvent): void {
  if (!isAssistant.value) return
  // 菜单不能超出视口：靠右靠下时往回收
  const x = Math.min(e.clientX, window.innerWidth - 160)
  const y = Math.min(e.clientY, window.innerHeight - 160)
  menu.value = { x, y }
}

async function copy(): Promise<void> {
  menu.value = null
  const ok = await copyText(props.message.text)
  show(ok ? '已复制' : '复制失败，请手动选择文本')
}

async function share(): Promise<void> {
  const ok = await shareAsImage({
    question: props.question || '健康咨询',
    answer: props.message.text,
    agent: agentLabel(props.message.agent),
  })
  show(ok ? '分享图已生成' : '生成失败，请重试')
}

function toggleSpeak(): void {
  menu.value = null
  if (speaking.value) {
    stopSpeaking()
    speaking.value = false
    return
  }
  speaking.value = true
  speak(props.message.text, () => {
    speaking.value = false
  })
}

function report(): void {
  menu.value = null
  emit('rate', 'report', '内容问题')
  show('已提交举报')
}

function rateUp(): void {
  emit('rate', 'up')
}

function startDown(): void {
  downOpen.value = true
}

function pickReason(reason: string): void {
  downOpen.value = false
  emit('rate', 'down', reason)
}

onBeforeUnmount(() => {
  clearPress()
  stopSpeaking()
})
</script>

<template>
  <div
    class="msg"
    :class="message.role"
    @contextmenu.prevent="openMenu"
    @pointerdown="startPress"
    @pointerup="clearPress"
    @pointerleave="clearPress"
  >
    <div class="msg-body">
      <span v-if="isAssistant && message.agent" class="badge">{{ agentLabel(message.agent) }}</span>
      <div class="bubble" :class="{ stopped: message.interrupted, 'has-image': !!message.image }">
        <img v-if="message.image" class="bubble-image" :src="message.image" alt="发送的图片" />
        <span v-if="message.text">{{ message.text }}</span>
        <span v-if="message.interrupted" class="stopped-tag">（已停止）</span>
      </div>

      <div v-if="isAssistant && canFeedback" class="actions">
        <button title="复制" @click="copy">复制</button>
        <button :class="{ on: message.rating === 'up' }" title="这条回答有用" @click="rateUp">有用</button>
        <button :class="{ on: message.rating === 'down' }" title="这条回答有问题" @click="startDown">没用</button>
        <button title="生成分享图" @click="share">分享</button>
      </div>

      <div v-if="downOpen" class="reasons">
        <span>哪里有问题？</span>
        <button v-for="r in reasons" :key="r" @click="pickReason(r)">{{ r }}</button>
        <button class="cancel" @click="downOpen = false">取消</button>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="menu" class="ctx-mask" @click="menu = null" @contextmenu.prevent="menu = null">
        <div class="ctx-menu" :style="{ left: menu.x + 'px', top: menu.y + 'px' }">
          <button @click="copy">复制</button>
          <button v-if="canSpeak()" @click="toggleSpeak">{{ speaking ? '停止朗读' : '朗读' }}</button>
          <button class="danger" @click="report">举报</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>
