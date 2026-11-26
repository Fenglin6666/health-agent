/** 前端共享类型。组件之间只通过这些结构通信，不传裸对象。 */

export type Role = 'user' | 'assistant' | 'route' | 'red_flag'

export interface ChatMessage {
  /** 本地自增 id，只用于 v-for key，不发后端 */
  id: number
  role: Role
  text: string
  /** 回答这条消息的 agent */
  agent?: string
  /** 第几轮，反馈需要 */
  turn?: number
  /** 追问候选，只有 assistant 消息可能有 */
  options?: string[]
  /** 被用户停止或连接中断 */
  interrupted?: boolean
  /** 已提交的反馈，用于按钮选中态 */
  rating?: 'up' | 'down' | 'report'
  /** 用户发送的图片（dataURL）。只存在于当前会话，刷新后后端不保留原图 */
  image?: string
}

export interface SessionSummary {
  id: string
  turns: number
  preview: string
  updatedAt: string
}

export interface GeoResult {
  found: boolean
  lat?: number
  lng?: number
  formatted_address?: string
  message?: string
}

export type StreamEvent =
  | { type: 'agent'; agent: string }
  | { type: 'handoff'; from: string; to: string }
  | { type: 'route'; target: string }
  | { type: 'red_flag'; message: string }
  | { type: 'token'; delta: string }
  | { type: 'clarify'; options: string[] }
  | { type: 'continue' }
  | { type: 'done'; turn?: number; streamed?: boolean }
  | { type: 'error'; message: string }
