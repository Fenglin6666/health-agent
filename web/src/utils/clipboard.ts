/**
 * 复制。优先 Clipboard API，失败退回 execCommand。
 *
 * 非 HTTPS 环境（比如局域网 IP 调试）没有 navigator.clipboard，
 * 不降级的话用户点了"复制"什么也不会发生，还以为按钮坏了。
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // 继续走降级
  }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.top = '-1000px'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(ta)
  return ok
}
