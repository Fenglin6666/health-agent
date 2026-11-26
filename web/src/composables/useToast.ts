import { ref } from 'vue'

export interface ToastItem {
  id: number
  text: string
}

// 全局单例：任何组件都能弹，不需要层层透传
const toasts = ref<ToastItem[]>([])
let seq = 0

export function useToast() {
  function show(text: string, ms = 2000): void {
    const id = ++seq
    toasts.value.push({ id, text })
    // 最多同时 3 条，多了互相盖住
    if (toasts.value.length > 3) toasts.value.shift()
    setTimeout(() => {
      toasts.value = toasts.value.filter(t => t.id !== id)
    }, ms)
  }

  return { toasts, show }
}
