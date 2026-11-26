# 问题记录：流式输出卡顿，最后一次性出现

日期：2026-11-26　｜　状态：已修复　｜　影响：所有流式回答的显示

---

## 1. 现象

回答开始后先出现一两个字，然后界面停住 1–2 秒，最后整段文字一次性出现。
网络看起来没问题（不是"转圈"），但用户感知就是"卡住了"。

---

## 2. 归因

### 2.1 先排除网络层

用带时间戳的脚本分别直连 8080、经 Vite 代理 5173 请求，记录每个 SSE 事件到达时间：

| 路径 | 事件数 | 首事件 | 最大间隔 |
|---|---:|---:|---:|
| 直连 core | 230 | 3.75s | 0.30s |
| 经 Vite 代理 | 259 | 3.77s | 0.29s |

**token 每 0.1–0.3 秒到一次，网络层是连续的。**

### 2.2 再看浏览器收到的 chunk

在页面里 patch `ReadableStream.prototype.getReader`，记录每次 `read()` 的时间：

```
136 个 chunk，2.4s 起连续到达，4.0s 结束
```

浏览器也收到了连续的 chunk。

### 2.3 最后看 DOM

同一条请求里同时采样 chunk 与 DOM 文本长度：

| 时间 | DOM 文本长度 |
|---:|---:|
| 2.7s | **2** |
| 5.5s | **441** |

中间 2.8 秒，DOM 长度没有任何变化——**token 收到了，但没触发渲染**。

### 2.4 根因：改了原始对象，不是响应式代理

`useChat` 里：

```ts
function push(msg) {
  const m = { id: ++seq, ...msg }
  messages.value.push(m)
  return m            // ← 返回的是原始对象
}

const m = push({ role: 'assistant', text: '' })
holder.current = m
holder.current.text += delta   // ← 改原始对象，绕过 Vue 的 Proxy
```

`messages.value.push(m)` 之后，Vue 在**读取**数组元素时才把它包成响应式代理。
`push` 返回的 `m` 始终是原始对象，改它的属性不会触发依赖更新。

之所以还能看到开头两个字：`push` 本身触发了一次渲染，
这次渲染发生在第一个 delta 写入之后，所以渲染出了"感冒"两个字。
之后所有 delta 都写进了原始对象，直到 `loading` 变成 `false` 引发整体重渲染，
才把累积的 441 字一次性画出来。

**这是重构引入的回归**：改造前是 `messages.value[idx].text += delta`（经过数组访问，拿到的是代理），
为了绕开 TypeScript 的类型收窄改成 `holder.current` 后，丢掉了响应式。

### 2.5 次要问题：滚动

`watch(messages, scrollToBottom, { deep: true })` 在每个 token 上都触发：

- deep watch 要遍历整个消息数组
- `scrollTo({ behavior: 'smooth' })` 每次都会取消上一个动画，长会话里追不上内容

不是这次卡顿的主因，但会加重"跟不上"的观感。

---

## 3. 修复

### 3.1 返回响应式代理（核心）

```ts
function push(msg): ChatMessage {
  const m = { id: ++seq, ...msg }
  messages.value.push(m)
  // 必须返回数组里的响应式代理；返回原始对象会导致流式更新不触发渲染
  return messages.value[messages.value.length - 1]
}
```

### 3.2 滚动节流

```ts
let scrollScheduled = false
function scheduleScroll(smooth = false) {
  if (scrollScheduled) return
  scrollScheduled = true
  requestAnimationFrame(() => {
    scrollScheduled = false
    listRef.value?.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  })
}

// 只观察"条数 + 最后一条文本长度"，不做深遍历
watch(() => `${messages.value.length}:${last?.text.length ?? 0}`, () => scheduleScroll(false))
watch(loading, busy => { if (!busy) scheduleScroll(true) })
```

流式期间用瞬时滚动（`auto`），一轮结束才用平滑动画。

---

## 4. 验证

同一条请求、同样的采样方式，修复前后对比：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| DOM 长度变化点 | 3 个（0 → 2 → 441） | **11 个**（12→15→73→87→191→…→432） |
| 最长无变化时间 | **2.8 秒** | 无（每 100–300ms 增长） |
| 流式期间离底部最大距离 | 未测（不跟随） | **72px** |

构建：`vue-tsc + vite build` 通过。

---

## 5. 防回归

1. **不要把响应式数组里的对象取出来裸改**。
   要改就通过数组下标（`arr[i].x = ...`）或 `push` 返回的代理。
2. **`push` 这类封装要么返回代理，要么不返回**，不要返回原始对象——
   调用方很容易拿它当响应式对象用。
3. **流式相关改动必须看 DOM 增长曲线**，不能只看"最后文字对不对"。
   这次如果只看最终回答，完全发现不了问题。
4. **滚动不要绑 deep watch**：观察便宜的信号（长度/条数），用 rAF 合并。

---

## 6. 一句话

不是网络慢，也不是模型慢，是**改了对象但没改到 Vue 看得见的那一份**。
流式链路的验证标准不是"最终内容正确"，而是"内容是否在持续增长"。
