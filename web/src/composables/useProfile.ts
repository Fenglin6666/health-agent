import { ref } from 'vue'
import { clearProfile, consolidate, fetchTimeline } from '../api/client'
import { useToast } from './useToast'

/**
 * 患者档案抽屉的状态。
 *
 * 从 App.vue 抽出来是因为它有三个异步动作 + 一段格式化逻辑，
 * 留在壳组件里会让 App.vue 重新长回 170 行——壳就该只负责布局。
 */
export function useProfile() {
  const digest = ref('')
  const timeline = ref('')
  const { show } = useToast()

  async function consolidateMemory(sessionId: string): Promise<void> {
    if (!sessionId) return
    try {
      const r = await consolidate(sessionId)
      digest.value = r.digest || '（暂无可提取的事实）'
      show(`记忆整理：写入 ${r.facts_written} 条事实`)
    } catch (e) {
      show(`整理失败：${(e as Error).message}`)
    }
  }

  async function toggleTimeline(sessionId: string): Promise<void> {
    if (!sessionId) return
    if (timeline.value) {
      timeline.value = ''
      return
    }
    try {
      const r = await fetchTimeline(sessionId)
      timeline.value = r.turns.map(t => {
        const head = `第 ${t.turn} 轮  [${t.status}]  ${t.agents.join(' → ')}`
        const evs = t.events
          .map(e => `    ${String(e.seq).padStart(2)} ${e.type.padEnd(16)} ${e.agent.padEnd(10)} ${JSON.stringify(e.detail)}`)
          .join('\n')
        return head + '\n' + evs
      }).join('\n\n')
    } catch (e) {
      timeline.value = `读取失败：${(e as Error).message}`
    }
  }

  async function clear(): Promise<void> {
    if (!window.confirm('会删除全部健康档案、待确认项和习惯记录，无法恢复。确定清空吗？')) return
    try {
      const r = await clearProfile()
      digest.value = r.digest
      show(`已清空：健康事实 ${r.facts_cleared} 条、待确认 ${r.contradictions_cleared} 条`)
    } catch (e) {
      show(`清空失败：${(e as Error).message}`)
    }
  }

  function resetTimeline(): void {
    timeline.value = ''
  }

  return { digest, timeline, consolidateMemory, toggleTimeline, clear, resetTimeline }
}
