/**
 * 生成分享图。纯前端 canvas，不把用户问答上传到服务端。
 *
 * 医疗内容分享出去会被截图二次传播，所以图里必须带免责声明——
 * 这不是装饰，是防止"一张图被当成诊断结论"的最后一道。
 */

const W = 900
const PAD = 48
const CONTENT_W = W - PAD * 2
const FONT = '"PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'

export interface ShareInput {
  question: string
  answer: string
  agent?: string
}

export async function shareAsImage(input: ShareInput): Promise<boolean> {
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) return false

  const qLines = wrap(ctx, input.question, CONTENT_W, `600 30px ${FONT}`)
  const answer = excerpt(input.answer, 320)
  const aLines = wrap(ctx, answer, CONTENT_W, `28px ${FONT}`)

  const qBlock = qLines.length * 42 + 44
  const aBlock = aLines.length * 42
  const height = 150 + qBlock + 36 + aBlock + 130

  const dpr = 2
  canvas.width = W * dpr
  canvas.height = height * dpr
  ctx.scale(dpr, dpr)

  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, W, height)

  // 顶部品牌条
  ctx.fillStyle = '#0d7a63'
  ctx.fillRect(0, 0, W, 8)

  ctx.fillStyle = '#16211e'
  ctx.font = `700 34px ${FONT}`
  ctx.fillText('健康管家', PAD, 76)

  ctx.fillStyle = '#6d7c78'
  ctx.font = `24px ${FONT}`
  const meta = input.agent ? `由 ${input.agent} 回答` : 'AI 健康咨询'
  ctx.fillText(meta, PAD, 112)

  // 问题块
  let y = 150
  ctx.fillStyle = '#eef2f1'
  roundRect(ctx, PAD, y, CONTENT_W, qBlock, 14)
  ctx.fill()

  ctx.fillStyle = '#16211e'
  ctx.font = `600 30px ${FONT}`
  y += 34
  for (const line of qLines) {
    ctx.fillText(line, PAD + 22, y)
    y += 42
  }

  // 回答
  y += 56
  ctx.fillStyle = '#16211e'
  ctx.font = `28px ${FONT}`
  for (const line of aLines) {
    ctx.fillText(line, PAD, y)
    y += 42
  }

  // 免责声明
  y = height - 52
  ctx.fillStyle = '#6d7c78'
  ctx.font = `22px ${FONT}`
  ctx.fillText('AI 生成，非医疗诊断，仅供参考。如有不适请及时就医。', PAD, y)

  return new Promise<boolean>(resolve => {
    canvas.toBlob(blob => {
      if (!blob) {
        resolve(false)
        return
      }
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `健康管家-${Date.now()}.png`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(url), 3000)
      resolve(true)
    }, 'image/png')
  })
}

function excerpt(text: string, max: number): string {
  const t = (text || '').replace(/\s+/g, ' ').trim()
  return t.length <= max ? t : t.slice(0, max) + '……'
}

/** 按像素宽度断行。中文没有空格，只能逐字量。 */
function wrap(ctx: CanvasRenderingContext2D, text: string, maxWidth: number, font: string): string[] {
  ctx.font = font
  const lines: string[] = []
  for (const para of (text || '').split('\n')) {
    let line = ''
    for (const ch of para) {
      const test = line + ch
      if (ctx.measureText(test).width > maxWidth && line) {
        lines.push(line)
        line = ch
      } else {
        line = test
      }
    }
    lines.push(line)
  }
  return lines.length ? lines : ['']
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}
