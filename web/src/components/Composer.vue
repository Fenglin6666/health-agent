<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { compressImage } from '../utils/image'
import { firstImage, imageCount } from '../utils/clipboardImage'
import { useToast } from '../composables/useToast'

const props = defineProps<{
  loading: boolean
  error: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string, image?: string): void
  (e: 'stop'): void
  (e: 'retry'): void
}>()

const { show } = useToast()

const input = ref('')
const preview = ref('')
const imageB64 = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

/** 选择文件和粘贴图片共用同一条入口：压缩 → 缩略图 → 待发送。 */
async function attach(file: File): Promise<void> {
  if (!file.type.startsWith('image/')) {
    show('只能发图片文件')
    return
  }
  try {
    const dataUrl = await compressImage(file)
    preview.value = dataUrl
    imageB64.value = dataUrl.split(',')[1] ?? ''
    await nextTick()
    // 粘贴后把焦点放回输入框，用户可以顺手补一句"这是今天的化验单"
    textareaRef.value?.focus()
  } catch {
    show('图片读取失败，换一张试试')
  }
}

async function pick(e: Event): Promise<void> {
  const el = e.target as HTMLInputElement
  const file = el.files?.[0]
  el.value = ''
  if (file) await attach(file)
}

/**
 * 全局粘贴监听。
 *
 * <p>挂在 window 而不是输入框：用户截完图不一定先点输入框，"随手 Ctrl+V"才是常态。
 * 代价是必须判断剪贴板内容——只有图片才接管，纯文本一律放行给浏览器默认行为，
 * 所以不会影响任何输入框的正常粘贴。
 */
function onPaste(e: ClipboardEvent): void {
  const data = e.clipboardData
  const file = firstImage(data)
  if (!file) return
  // 有图就拦掉默认行为：否则从网页/Word 复制时会把富文本一起塞进输入框
  e.preventDefault()
  void attach(file)
  if (imageCount(data) > 1) show('一次只能发一张，已取第一张')
}

onMounted(() => window.addEventListener('paste', onPaste))
onBeforeUnmount(() => window.removeEventListener('paste', onPaste))

function clearImage(): void {
  preview.value = ''
  imageB64.value = ''
}

function submit(): void {
  if (props.loading || props.disabled) return
  const text = input.value.trim()
  if (!text && !imageB64.value) return
  // 发 dataURL 而不是裸 base64：气泡里要直接 <img> 显示，
  // 裸 base64 当 src 会解析成页面地址（实测 src 变成 http://localhost:5173/）
  emit('send', text, preview.value || undefined)
  input.value = ''
  clearImage()
}
</script>

<template>
  <footer>
    <div v-if="error" class="retry-bar">
      <span>{{ error }}</span>
      <button @click="emit('retry')">重试</button>
    </div>

    <div class="composer" :class="{ busy: loading }">
      <div v-if="preview" class="attach-preview">
        <img :src="preview" alt="待发送的图片" />
        <button class="attach-remove" title="移除" @click="clearImage">×</button>
      </div>
      <div class="composer-row">
        <button class="icon-btn" title="发送图片（也可以直接 Ctrl+V 粘贴）" :disabled="loading || disabled" @click="fileInput?.click()">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="4" width="18" height="16" rx="3" />
            <circle cx="9" cy="10" r="1.8" />
            <path d="M4 17l4.5-4.5a2 2 0 0 1 2.8 0L20 21" />
          </svg>
        </button>
        <input ref="fileInput" type="file" accept="image/*" hidden @change="pick" />
        <textarea
          ref="textareaRef"
          v-model="input"
          rows="1"
          :disabled="loading || disabled"
          placeholder="描述症状、问药品，或粘贴/上传报告截图…"
          @keydown.enter.exact.prevent="submit"
        />
        <button
          v-if="loading"
          class="send-btn stop"
          title="停止生成"
          @click="emit('stop')"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <rect x="7" y="7" width="10" height="10" rx="2" />
          </svg>
        </button>
        <button
          v-else
          class="send-btn"
          :disabled="disabled || (!input.trim() && !imageB64)"
          @click="submit"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
               stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 12h15M13 6l6 6-6 6" />
          </svg>
        </button>
      </div>
    </div>
  </footer>
</template>
