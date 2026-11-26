import { ref } from 'vue'
import { createSession, fetchSession, fetchSessions } from '../api/client'
import type { SessionSummary } from '../types'

const STORAGE_KEY = 'ha.sessionId'
const USER_ID = 'local'

/**
 * 会话列表与当前会话。
 *
 * 只负责"有哪些会话、当前是哪个"，不碰消息内容——
 * 消息由 useChat 管，切换会话时由视图层把两者接起来。
 */
export function useSessions() {
  const sessions = ref<SessionSummary[]>([])
  const currentId = ref('')
  const loading = ref(false)

  function persist(id: string): void {
    try {
      localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // 隐私模式下 localStorage 会抛异常，退化成"刷新后新建会话"
    }
  }

  function readStored(): string {
    try {
      return localStorage.getItem(STORAGE_KEY) ?? ''
    } catch {
      return ''
    }
  }

  async function load(): Promise<void> {
    try {
      sessions.value = await fetchSessions(USER_ID)
    } catch {
      sessions.value = []
    }
  }

  async function createNew(): Promise<string> {
    const s = await createSession(USER_ID)
    currentId.value = s.session_id
    persist(s.session_id)
    await load()
    return s.session_id
  }

  async function switchTo(id: string): Promise<void> {
    currentId.value = id
    persist(id)
  }

  /**
   * 刷新恢复：只认领已有的会话，**不创建**。
   *
   * <p>之前这里失败就新建，导致每次打开页面都往库里写一条空会话——
   * 开发时预览面板一热更新就是一条，历史里堆出一排"（无标题）0 轮"。
   * 会话应该在用户发出第一条消息时才存在。
   */
  async function restore(): Promise<void> {
    loading.value = true
    try {
      const stored = readStored()
      if (stored) {
        try {
          await fetchSession(stored)
          currentId.value = stored
        } catch {
          currentId.value = ''
        }
      }
      await load()
    } finally {
      loading.value = false
    }
  }

  /**
   * 新对话：只把当前会话置空，**不建会话**。
   * 建会话推迟到用户发出第一条消息，避免"点了新对话又没说话"留下一行空记录。
   */
  function reset(): void {
    currentId.value = ''
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      // 忽略：隐私模式下本来就没有
    }
  }

  /** 首次发消息前调用：没有会话就建一个，有就直接用。 */
  async function ensure(): Promise<string> {
    return currentId.value || createNew()
  }

  return { sessions, currentId, loading, load, createNew, switchTo, restore, ensure, reset }
}
