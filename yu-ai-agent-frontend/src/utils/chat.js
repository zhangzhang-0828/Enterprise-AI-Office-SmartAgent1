export const CONNECTION_STATUS = {
  IDLE: 'idle',
  CONNECTING: 'connecting',
  STREAMING: 'streaming',
  ERROR: 'error'
}

const formatParams = (params = {}) => {
  const searchParams = new URLSearchParams()

  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== '') {
      searchParams.append(key, value)
    }
  })

  return searchParams.toString()
}

export const createSseConnection = (baseUrl, path, params = {}) => {
  const queryString = formatParams(params)
  const fullUrl = queryString ? `${baseUrl}${path}?${queryString}` : `${baseUrl}${path}`

  return new EventSource(fullUrl)
}

export const closeSseConnection = (eventSource) => {
  if (eventSource) {
    eventSource.close()
  }
}

/**
 * 正确的 SSE 协议解析器。
 *
 * 背景：Spring SseEmitter.send(content) 会发送 "data: <content>\n\n"。
 * 但 TCP 分包可能导致一行内容被截断成多个 chunk（如 "data: 这道" 和 "菜是..."）。
 * 浏览器 EventSource.onmessage 按行触发，但截断行会被丢弃。
 * 这个解析器正确处理：缓冲行直到遇到 \n\n 事件边界，才组装完整事件。
 *
 * @param {ReadableStreamDefaultReader} reader - fetch() 返回的 body.reader
 * @param {TextDecoder} decoder - 文本解码器
 * @param {{ onChunk, onDone, onError }} callbacks
 * @returns {() => void} 取消函数
 */
export function createSSEParser(reader, decoder, { onChunk, onDone, onError }) {
  let buffer = ''          // 未处理的文本
  let eventBuffer = ''     // 当前事件的 data 行拼接结果
  let cancelled = false

  const processBuffer = () => {
    while (buffer.length > 0) {
      // 查找最近的 \n\n（事件结束标记）
      const eventEnd = buffer.indexOf('\n\n')
      if (eventEnd === -1) {
        // 没有完整的 \n\n，先检查有没有单独的 \n（行结束）
        const lineEnd = buffer.indexOf('\n')
        if (lineEnd !== -1) {
          const line = buffer.slice(0, lineEnd)
          buffer = buffer.slice(lineEnd + 1)
          handleLine(line)
        }
        // 剩余不完整，留在 buffer 里等下一个 chunk
        break
      }

      // 有一个完整事件（含 \n\n）
      const eventData = buffer.slice(0, eventEnd + 2) // 含结尾的 \n
      buffer = buffer.slice(eventEnd + 2)

      // 处理事件中所有行
      const lines = splitLines(eventData)
      for (const line of lines) {
        handleLine(line)
      }
      // 事件完整，触发 emit
      if (eventBuffer.length > 0) {
        const payload = eventBuffer
        eventBuffer = ''
        onChunk(payload)
      }
    }
  }

  const handleLine = (line) => {
    if (line.startsWith('data:')) {
      const data = line.slice(5)
      // 支持 "data: xxx\ndata: yyy" 多行 SSE 格式
      eventBuffer += (eventBuffer ? '\n' : '') + data
    }
    // 其他行（event: / id: / retry:）直接忽略，SSE 标准仅 data: 携带内容
  }

  const splitLines = (text) => {
    // 按 \n 分割，过滤空行（末尾多余的 \n）
    return text.split('\n').filter(line => line.length > 0)
  }

  const readLoop = () => {
    if (cancelled) return
    reader.read().then(({ done, value }) => {
      if (done || cancelled) {
        onDone()
        return
      }
      buffer += decoder.decode(value, { stream: true })
      processBuffer()
      readLoop()
    }).catch(err => {
      if (!cancelled) onError(err)
    })
  }

  readLoop()

  // 返回取消函数
  return () => {
    cancelled = true
    reader.cancel().catch(() => {})
  }
}

export const createMessage = (content, overrides = {}) => ({
  id: overrides.id ?? `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  content,
  isUser: false,
  status: CONNECTION_STATUS.IDLE,
  time: Date.now(),
  ...overrides
})

export const buildErrorMessage = (fallback = '连接中断，请稍后重试') =>
  createMessage(fallback, {
    type: 'system',
    status: CONNECTION_STATUS.ERROR
  })

export const generateChatId = (prefix = 'chat') => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${prefix}_${crypto.randomUUID()}`
  }

  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}
