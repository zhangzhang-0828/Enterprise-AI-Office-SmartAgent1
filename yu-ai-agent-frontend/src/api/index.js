const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.trim() || '/api'

const formatParams = (params = {}) => {
  const searchParams = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== '') {
      searchParams.append(key, value)
    }
  })
  return searchParams.toString()
}

export const connectSSE = (url, params = {}) => {
  const queryString = formatParams(params)
  const fullUrl = queryString ? `${API_BASE_URL}${url}?${queryString}` : `${API_BASE_URL}${url}`
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
  status: 'idle',
  time: Date.now(),
  ...overrides
})

export const buildErrorMessage = (fallback = '连接中断，请稍后重试') =>
  createMessage(fallback, { type: 'system', status: 'error' })

export const generateChatId = (prefix = 'chat') =>
  `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`

// ==================== 恋爱大师接口 ====================

export const chatWithLoveApp = (message, chatId) =>
  connectSSE('/ai/love_app/chat/sse', { message, chatId })

/**
 * 恋爱大师多模态对话（POST + FormData，支持图片上传）
 * 返回 { promise, controller }，调用方负责消费流
 */
export const chatWithLoveAppWithImage = (message, chatId, imageFile = null) => {
  const formData = new FormData()
  formData.append('message', message)
  if (chatId) formData.append('chatId', chatId)
  if (imageFile) formData.append('image', imageFile)
  const controller = new AbortController()
  const promise = fetch(`${API_BASE_URL}/ai/love_app/chat/sse_emitter`, {
    method: 'POST',
    body: formData,
    signal: controller.signal
  }).then(async response => {
    if (!response.ok) {
      const text = await response.text().catch(() => response.statusText)
      throw new Error(`请求失败 (${response.status}): ${text}`)
    }
    return response.body
  })
  return { promise, controller }
}

// ==================== 超级智能体接口 ====================

export const chatWithManus = (message) =>
  connectSSE('/ai/manus/chat', { message })

/**
 * 超级智能体多模态对话（POST + FormData，支持图片上传）
 * 返回 { promise, controller }
 */
export const chatWithManusWithImage = (message, imageFile = null) => {
  const formData = new FormData()
  formData.append('message', message)
  if (imageFile) formData.append('image', imageFile)
  const controller = new AbortController()
  const promise = fetch(`${API_BASE_URL}/ai/manus/chat`, {
    method: 'POST',
    body: formData,
    signal: controller.signal
  }).then(async response => {
    if (!response.ok) {
      const text = await response.text().catch(() => response.statusText)
      throw new Error(`请求失败 (${response.status}): ${text}`)
    }
    return response.body
  })
  return { promise, controller }
}

export default {
  chatWithLoveApp,
  chatWithLoveAppWithImage,
  chatWithManus,
  chatWithManusWithImage
}
