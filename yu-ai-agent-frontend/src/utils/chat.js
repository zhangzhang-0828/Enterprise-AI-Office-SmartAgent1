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
