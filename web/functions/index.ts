/**
 * Sites Edge Function：同源反代到本地后端（经 cloudflared 隧道）。
 * 只转发 /v1/ 业务路径，其余一律 404，避免变成开放代理。
 *
 * 隧道地址优先读环境变量 BACKEND_BASE；未配置时用默认值兜底，
 * 换隧道只需改这里或注入变量。
 */
const BACKEND_BASE = Deno.env.get('BACKEND_BASE') ?? 'https://asp-fig-johnson-supporters.trycloudflare.com'
const UPSTREAM_TIMEOUT_MS = 300_000

const ALLOWED_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'])

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

Deno.serve(async (req: Request): Promise<Response> => {
  const url = new URL(req.url)

  // 没配 BACKEND_BASE 就明确失败，不要静默代理到空地址
  if (!BACKEND_BASE) return json(503, { error: 'backend_not_configured' })

  if (!ALLOWED_METHODS.has(req.method)) {
    return json(405, { error: 'method_not_allowed' })
  }
  if (req.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'access-control-allow-methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
        'access-control-allow-headers': 'content-type, accept',
      },
    })
  }

  // 网关可能把函数名前缀替换成上游物理名，因此按 /v1/ 截断，不假设完整路径。
  if (!BACKEND_BASE) return json(503, { error: 'service_not_configured' })

  const marker = url.pathname.indexOf('/v1/')
  if (marker < 0) return json(404, { error: 'not_found' })

  const upstreamUrl = `${BACKEND_BASE}/api${url.pathname.slice(marker)}${url.search}`

  const headers = new Headers()
  const contentType = req.headers.get('content-type')
  const accept = req.headers.get('accept')
  if (contentType) headers.set('content-type', contentType)
  if (accept) headers.set('accept', accept)

  const hasBody = req.method !== 'GET' && req.method !== 'HEAD'

  let upstream: Response
  try {
    upstream = await fetch(upstreamUrl, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
      redirect: 'manual',
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    })
  } catch {
    return json(502, { error: 'backend_unreachable' })
  }

  const responseHeaders = new Headers()
  const upstreamType = upstream.headers.get('content-type')
  if (upstreamType) responseHeaders.set('content-type', upstreamType)
  responseHeaders.set('cache-control', 'no-store')
  // 对话接口是 SSE，关闭中间层缓冲，保证逐 token 下发。
  responseHeaders.set('x-accel-buffering', 'no')

  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders })
})