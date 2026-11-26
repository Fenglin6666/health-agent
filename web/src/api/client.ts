/**
 * API 客户端。所有请求走这里，组件里不直接 fetch。
 */

import type {
  ChatMessage, GeoResult, SessionSummary, StreamEvent,
} from '../types'

/**
 * 本地开发走 Vite 代理直连 8080；线上走站点 Edge Function 反代。
 */
const API_BASE = import.meta.env.DEV ? '/api' : '/functions/v1/app'

export interface RouteEvent {
  target: string
  layer?: string
  confidence?: number
}

export interface RedFlagEvent {
  id: string
  message: string
}

export interface DoneEvent {
  safety_issues?: string[]
  steps?: number
  elapsed_ms?: number
  agent?: string
  handoffs?: number
  turn?: number
}

export interface AgentEvent {
  agent: string
  turn: number
  resumed?: boolean
}

export interface HandoffEvent {
  from: string
  to: string
}

export interface TimelineEvent { seq: number; type: string; agent: string; detail: Record<string, unknown> }
export interface TimelineMessage { seq: number; role: string; agent: string; content: string }
export interface TimelineTurn {
  turn: number
  status: string
  agents: string[]
  messages: TimelineMessage[]
  events: TimelineEvent[]
}

// ---------------------------------------------------------------- 会话

export async function createSession(userId = 'local'): Promise<{ session_id: string; profile_digest: string }> {
  const res = await fetch(`${API_BASE}/v1/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  })
  if (!res.ok) throw new Error(`创建会话失败：${res.status}`)
  return res.json()
}

export async function fetchSessions(userId = 'local', limit = 20): Promise<SessionSummary[]> {
  const res = await fetch(`${API_BASE}/v1/sessions?userId=${encodeURIComponent(userId)}&limit=${limit}`)
  if (!res.ok) throw new Error(`读取会话列表失败：${res.status}`)
  const body = await res.json() as { sessions: SessionSummary[] }
  return body.sessions ?? []
}

export async function fetchSession(sessionId: string): Promise<{ session_id: string }> {
  const res = await fetch(`${API_BASE}/v1/sessions/${sessionId}`)
  if (!res.ok) throw new Error(`会话不存在：${res.status}`)
  return res.json()
}

/** 带状态的全量历史：每轮的消息 + 事件流。 */
export async function fetchTimeline(sessionId: string): Promise<{ turns: TimelineTurn[] }> {
  const res = await fetch(`${API_BASE}/v1/sessions/${sessionId}/timeline`)
  if (!res.ok) throw new Error(`读取状态流失败：${res.status}`)
  return res.json()
}

// ---------------------------------------------------------------- 反馈

export interface FeedbackInput {
  sessionId: string
  turn: number
  agent?: string
  rating: 'up' | 'down' | 'report'
  reason?: string
  comment?: string
}

export async function submitFeedback(input: FeedbackInput): Promise<void> {
  const res = await fetch(`${API_BASE}/v1/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sessionId: input.sessionId,
      turn: input.turn,
      agent: input.agent ?? '',
      rating: input.rating,
      reason: input.reason ?? '',
      comment: input.comment ?? '',
    }),
  })
  if (!res.ok) throw new Error(`反馈提交失败：${res.status}`)
}

// ---------------------------------------------------------------- 对话

export interface SendOptions {
  imageBase64?: string
  signal?: AbortSignal
}

/**
 * 发送消息并逐事件回调。
 *
 * <p>后端用 SSE，前端手动解析——不引 eventsource，因为需要 POST 和自定义头。
 * 事件类型必须与后端 emit 的名字一一对应；少一个就会出现"后端发了、前端没反应"，
 * 之前 agent/handoff 就是这么丢的。
 */
export async function sendMessage(
  sessionId: string,
  text: string,
  onEvent: (e: StreamEvent) => void,
  options: SendOptions = {},
): Promise<void> {
  const res = await fetch(`${API_BASE}/v1/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, imageBase64: options.imageBase64 || null }),
    signal: options.signal,
  })
  if (!res.ok || !res.body) throw new Error(`请求失败：${res.status}`)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''

    for (const block of blocks) {
      let event = 'message'
      const dataLines: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (!dataLines.length) continue
      const raw = dataLines.join('\n')
      let parsed: unknown
      try {
        parsed = JSON.parse(raw)
      } catch {
        parsed = raw
      }
      dispatch(event, parsed, onEvent)
    }
  }
}

function dispatch(event: string, parsed: unknown, onEvent: (e: StreamEvent) => void): void {
  switch (event) {
    case 'agent':
      onEvent({ type: 'agent', agent: String((parsed as AgentEvent).agent ?? '') })
      break
    case 'handoff': {
      const h = parsed as HandoffEvent
      onEvent({ type: 'handoff', from: String(h.from ?? ''), to: String(h.to ?? '') })
      break
    }
    case 'route':
      onEvent({ type: 'route', target: String((parsed as RouteEvent).target ?? '') })
      break
    case 'red_flag':
      onEvent({ type: 'red_flag', message: String((parsed as RedFlagEvent).message ?? '') })
      break
    case 'token':
      onEvent({ type: 'token', delta: String((parsed as { delta?: string }).delta ?? '') })
      break
    case 'clarify': {
      const opts = (parsed as { options?: string[] }).options ?? []
      onEvent({ type: 'clarify', options: opts.map(String) })
      break
    }
    case 'continue':
      onEvent({ type: 'continue' })
      break
    case 'done':
      onEvent({ type: 'done', turn: (parsed as DoneEvent).turn })
      break
    case 'error':
      onEvent({ type: 'error', message: String((parsed as { message?: string }).message ?? '未知错误') })
      break
    default:
      // 未知事件忽略：新增事件不能把老客户端打挂
      break
  }
}

// ---------------------------------------------------------------- 其它

export async function fetchRoutingStats(): Promise<Record<string, number>> {
  const res = await fetch(`${API_BASE}/v1/routing/stats`)
  return res.ok ? res.json() : {}
}

export async function geocode(address: string): Promise<GeoResult> {
  const res = await fetch(`${API_BASE}/v1/places/geocode?address=${encodeURIComponent(address)}`)
  if (!res.ok) throw new Error(`解析地址失败：${res.status}`)
  return res.json()
}

export async function setLocation(sessionId: string, lat: number, lng: number): Promise<void> {
  await fetch(`${API_BASE}/v1/sessions/${sessionId}/location`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ lat, lng }),
  })
}

export async function consolidate(sessionId: string): Promise<{ facts_written: number; digest: string }> {
  const res = await fetch(`${API_BASE}/v1/sessions/${sessionId}/consolidate`, { method: 'POST' })
  if (!res.ok) throw new Error(`整理失败：${res.status}`)
  return res.json()
}

export interface ClearProfileResult {
  ok: boolean
  owner: string
  facts_cleared: number
  contradictions_cleared: number
  digest: string
}

export async function clearProfile(): Promise<ClearProfileResult> {
  const res = await fetch(`${API_BASE}/v1/memory/profile`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`清空失败：${res.status}`)
  return res.json()
}

/** 时间线 → 聊天气泡。只保留 USER / ASSISTANT，工具结果对用户没有意义。 */
export function timelineToMessages(turns: TimelineTurn[]): ChatMessage[] {
  const out: ChatMessage[] = []
  let id = 1
  for (const t of turns) {
    for (const m of t.messages) {
      if (m.role === 'USER') {
        out.push({ id: id++, role: 'user', text: m.content })
      } else if (m.role === 'ASSISTANT') {
        out.push({ id: id++, role: 'assistant', text: m.content, agent: m.agent, turn: t.turn })
      }
    }
  }
  return out
}
