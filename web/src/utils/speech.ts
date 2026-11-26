/**
 * 朗读。用浏览器自带的语音合成，不依赖后端。
 *
 * 医学长文本朗读对老年人和视障用户是刚需，但各家语音质量差异很大，
 * 所以只做"能读、能停"，不追求音色。
 */

export function canSpeak(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window
}

let current: SpeechSynthesisUtterance | null = null

export function speak(text: string, onEnd?: () => void): void {
  if (!canSpeak() || !text) return
  stopSpeaking()
  const u = new SpeechSynthesisUtterance(text)
  u.lang = 'zh-CN'
  u.rate = 1
  u.onend = () => {
    current = null
    onEnd?.()
  }
  u.onerror = () => {
    current = null
    onEnd?.()
  }
  current = u
  window.speechSynthesis.speak(u)
}

export function stopSpeaking(): void {
  if (!canSpeak()) return
  current = null
  window.speechSynthesis.cancel()
}

export function isSpeaking(): boolean {
  return current !== null
}
