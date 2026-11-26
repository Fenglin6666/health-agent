import { ref, type Ref } from 'vue'
import { sendMessage, submitFeedback } from '../api/client'
import { agentLabel } from '../agents'
import type { ChatMessage } from '../types'

/**
 * 对话状态机。
 *
 * 一条消息的生命周期：发送 → 若干 token → done / 中断 / 失败。
 * 所有状态都收在这里，组件只读不算——否则"这条消息到底在不在生成中"
 * 会散落在多个组件里，最后没人说得清。
 */
export function useChat() {
  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const error = ref('')

  let controller: AbortController | null = null
  let seq = 0
  let lastUser: { text: string; image?: string } | null = null
  let lastSessionId = ''

  function push(msg: Omit<ChatMessage, 'id'>): ChatMessage {
    const m = { id: ++seq, ...msg } as ChatMessage
    messages.value.push(m)
    // 必须返回数组里的**响应式代理**，不能返回原始对象。
    // 直接改原始对象的属性不会触发 Vue 更新：流式 token 会全部堆在内存里，
    // 直到 loading 变化引发一次整体重渲染，表现为"先出一两个字，卡住，然后全部出现"。
    return messages.value[messages.value.length - 1]
  }

  async function send(sessionId: string, text: string, image?: string): Promise<void> {
    if (loading.value || !sessionId) return
    const trimmed = text.trim()
    if (!trimmed && !image) return

    lastSessionId = sessionId
    lastUser = { text: trimmed, image }
    // 有图就带 dataURL：当前会话里直接把图显示出来，而不是只给一句"（图片）"
    push({ role: 'user', text: trimmed, image })
    loading.value = true
    error.value = ''
    controller = new AbortController()

    let currentAgent = ''
    // 用对象持有而不是裸变量：回调里赋值时，TS 的控制流分析会认为外层仍是 null，
    // 后面判空之后类型被收窄成 never，编译直接报错。
    const holder: { current: ChatMessage | null } = { current: null }
    let pendingOptions: string[] = []
    const created: ChatMessage[] = []

    try {
      await sendMessage(sessionId, trimmed, e => {
        switch (e.type) {
          case 'agent':
            currentAgent = e.agent
            break
          case 'handoff':
            currentAgent = e.to
            push({ role: 'route', text: `转接：${agentLabel(e.from)} → ${agentLabel(e.to)}` })
            break
          case 'red_flag':
            push({ role: 'red_flag', text: e.message })
            break
          case 'token': {
            if (!holder.current) {
              const m = push({
                role: 'assistant',
                text: '',
                agent: currentAgent,
                options: pendingOptions.length ? [...pendingOptions] : undefined,
              })
              holder.current = m
              pendingOptions = []
              created.push(m)
            }
            holder.current.text += e.delta
            break
          }
          case 'clarify':
            pendingOptions = e.options
            if (holder.current) holder.current.options = e.options
            break
          case 'continue':
            // 同一轮的第二段：下一批 token 要开新气泡，不能拼在上一条后面
            holder.current = null
            break
          case 'done':
            for (const m of created) {
              if (!m.turn) m.turn = e.turn
            }
            break
          case 'error':
            error.value = e.message
            if (holder.current) holder.current.interrupted = true
            break
          default:
            break
        }
      }, { imageBase64: toPayload(image), signal: controller.signal })
    } catch (e) {
      const err = e as Error
      if (err.name === 'AbortError') {
        if (holder.current) holder.current.interrupted = true
        else push({ role: 'route', text: '已停止生成' })
      } else {
        error.value = err.message || '请求失败'
        if (holder.current) holder.current.interrupted = true
      }
    } finally {
      loading.value = false
      controller = null
    }
  }

  /** 接口只收裸 base64；界面渲染要 dataURL。两者只在这里转换一次。 */
  function toPayload(image?: string): string | undefined {
    if (!image) return undefined
    return image.startsWith('data:') ? image.split(',')[1] ?? '' : image
  }

  function stop(): void {
    controller?.abort()
  }

  async function retry(): Promise<void> {
    if (!lastUser || loading.value) return
    // 重试前把失败的那条回答标掉，避免界面上出现两条并列的"失败回答"
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role === 'assistant') {
        if (m.interrupted) messages.value.splice(i, 1)
        break
      }
    }
    await send(lastSessionId, lastUser.text, lastUser.image)
  }

  async function rate(sessionId: string, m: ChatMessage,
                      rating: 'up' | 'down' | 'report', reason?: string): Promise<boolean> {
    if (!sessionId || !m.turn) return false
    try {
      await submitFeedback({
        sessionId,
        turn: m.turn,
        agent: m.agent,
        rating,
        reason,
      })
      m.rating = rating
      return true
    } catch {
      return false
    }
  }

  /** 往对话里插一条系统提示（定位成功、记忆整理结果等）。 */
  function note(text: string): void {
    push({ role: 'route', text })
  }

  function clear(): void {
    messages.value = []
    error.value = ''
    lastUser = null
    seq = 0
  }

  function setMessages(list: ChatMessage[]): void {
    messages.value = list
    seq = list.reduce((max, m) => Math.max(max, m.id), 0)
    error.value = ''
  }

  return { messages, loading, error, send, stop, retry, rate, note, clear, setMessages }
}
