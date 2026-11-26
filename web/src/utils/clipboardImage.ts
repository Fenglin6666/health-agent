/**
 * 从剪贴板数据里取图片。
 *
 * 单独抽成纯函数是为了能脱离浏览器单测：
 * 这里最容易出错的是"文本和图片混在一起"以及"Safari 只给 files 不给 items"。
 */

export interface ClipboardItemLike {
  type: string
  getAsFile(): File | null
}

export interface ClipboardLike {
  items?: ArrayLike<ClipboardItemLike>
  files?: ArrayLike<File>
}

/** 收集所有图片文件，顺序与剪贴板一致。 */
function collect(data: ClipboardLike | null | undefined): File[] {
  if (!data) return []
  const out: File[] = []

  if (data.items) {
    for (let i = 0; i < data.items.length; i++) {
      const item = data.items[i]
      if (!item || typeof item.type !== 'string' || !item.type.startsWith('image/')) {
        continue
      }
      // 某些虚拟文件 getAsFile() 返回 null，跳过继续找
      const file = item.getAsFile()
      if (file) out.push(file)
    }
  }

  // Safari 粘贴文件时 items 可能为空，只有 files
  if (!out.length && data.files) {
    for (let i = 0; i < data.files.length; i++) {
      const file = data.files[i]
      if (file && typeof file.type === 'string' && file.type.startsWith('image/')) {
        out.push(file)
      }
    }
  }

  return out
}

/** 第一张图片；没有返回 null。 */
export function firstImage(data: ClipboardLike | null | undefined): File | null {
  return collect(data)[0] ?? null
}

/** 图片数量，用于"一次只能发一张"的提示。 */
export function imageCount(data: ClipboardLike | null | undefined): number {
  return collect(data).length
}
