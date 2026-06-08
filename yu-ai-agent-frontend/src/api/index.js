import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.trim() || '/api'

const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 120000
})

export const connectSSE = (url, params = {}) => {
  const searchParams = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== '') {
      searchParams.append(key, value)
    }
  })
  const queryString = searchParams.toString()
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

export const chatWithLoveApp = (message, chatId) => connectSSE('/ai/love_app/chat/sse', { message, chatId })

export const chatWithManus = (message) => connectSSE('/ai/manus/chat', { message })

/**
 * 多模态流式对话（POST + Fetch + ReadableStream）。
 * 支持发送文本消息和可选图片文件，流式接收 AI 回复。
 *
 * @param {string} message - 文本消息
 * @param {File|null} imageFile - 图片文件（可选）
 * @returns {{ promise: Promise<ReadableStream>, controller: AbortController }}
 */
export const createMultimodalStream = (message, imageFile = null) => {
  const formData = new FormData()
  formData.append('message', message)
  if (imageFile) {
    formData.append('image', imageFile)
  }
  const controller = new AbortController()
  const promise = fetch(`${API_BASE_URL}/ai/multimodal/chat/sse_emitter`, {
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

export default { chatWithLoveApp, chatWithManus, createMultimodalStream }
