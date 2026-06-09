<template>
  <div class="chat-page love-master-page">
    <div class="page-content">
      <ChatRoom
        :messages="messages"
        :connection-status="connectionStatus"
        :status-label="statusLabel"
        :can-retry="canRetry"
        :can-go-back="true"
        ai-type="love"
        title="AI 恋爱大师"
        eyebrow="情感陪伴"
        :subtitle="subtitle"
        placeholder="说说你的感受、困惑或想法..."
        @send-message="sendMessage"
        @cancel="cancelStream"
        @retry="retryLastMessage"
        @go-back="goBack"
      />
    </div>

    <AppFooter />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithLoveApp, chatWithLoveAppWithImage } from '../api'
import {
  buildErrorMessage,
  closeSseConnection,
  CONNECTION_STATUS,
  createMessage,
  createSSEParser,
  generateChatId
} from '../utils/chat'

useHead({
  title: 'AI恋爱大师 - kyrieAI超级智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'AI恋爱大师是kyrieAI超级智能体应用平台的专业情感顾问，帮你解答各种恋爱问题，提供情感建议'
    },
    {
      name: 'keywords',
      content: 'AI恋爱大师,情感顾问,恋爱咨询,AI聊天,情感问题,kyrie,AI智能体'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref(CONNECTION_STATUS.IDLE)
const lastSubmittedMessage = ref('')
const lastSubmittedImage = ref(null)
let eventSource = null
let abortController = null
let cancelSSE = null

const subtitle = computed(() => `当前会话：${chatId.value || '正在初始化'}，支持流式回复与连续追问。`)

const statusLabel = computed(() => {
  switch (connectionStatus.value) {
    case CONNECTION_STATUS.CONNECTING:
      return '正在建立连接'
    case CONNECTION_STATUS.STREAMING:
      return '正在回复'
    case CONNECTION_STATUS.ERROR:
      return '连接异常'
    default:
      return '准备就绪'
  }
})

const canRetry = computed(() => Boolean(lastSubmittedMessage.value) && connectionStatus.value === CONNECTION_STATUS.ERROR)

const addMessage = (content, overrides = {}) => {
  const message = createMessage(content, overrides)
  messages.value.push(message)
  return message
}

const removeEmptyAssistantMessage = (messageId) => {
  if (!messageId) return
  const targetIndex = messages.value.findIndex(message => message.id === messageId && !message.content.trim())
  if (targetIndex !== -1) {
    messages.value.splice(targetIndex, 1)
  }
}

const cancelStream = () => {
  if (eventSource) {
    closeSseConnection(eventSource)
    eventSource = null
  }
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  if (cancelSSE) {
    cancelSSE()
    cancelSSE = null
  }
  connectionStatus.value = CONNECTION_STATUS.IDLE
}

const handleStreamError = (placeholderId) => {
  closeSseConnection(eventSource)
  eventSource = null
  connectionStatus.value = CONNECTION_STATUS.ERROR
  removeEmptyAssistantMessage(placeholderId)
  addMessage(buildErrorMessage('连接已中断，请稍后重试或重新发送。').content, {
    isUser: false,
    type: 'system',
    status: CONNECTION_STATUS.ERROR
  })
}

const sendMessage = (message, imageFile = null) => {
  const trimmedMessage = message.trim()
  if (!trimmedMessage && !imageFile) return

  cancelStream()

  lastSubmittedMessage.value = trimmedMessage
  lastSubmittedImage.value = imageFile
  const userImageUrl = imageFile ? URL.createObjectURL(imageFile) : null
  addMessage(trimmedMessage, {
    isUser: true,
    type: 'user-question',
    imageUrl: userImageUrl
  })

  const aiMessage = addMessage('', {
    isUser: false,
    type: 'ai-answer',
    status: CONNECTION_STATUS.CONNECTING
  })

  connectionStatus.value = CONNECTION_STATUS.CONNECTING

  if (imageFile) {
    // 多模态：POST + FormData
    const { promise, controller } = chatWithLoveAppWithImage(trimmedMessage, chatId.value, imageFile)
    abortController = controller

    promise.then(async (body) => {
      if (!body) {
        aiMessage.content = '服务器返回了空响应'
        aiMessage.status = CONNECTION_STATUS.ERROR
        aiMessage.type = 'system'
        connectionStatus.value = CONNECTION_STATUS.ERROR
        return
      }
      const reader = body.getReader()
      const decoder = new TextDecoder()
      connectionStatus.value = CONNECTION_STATUS.STREAMING
      aiMessage.status = CONNECTION_STATUS.STREAMING
      cancelSSE = createSSEParser(reader, decoder, {
        onChunk: (data) => {
          if (data === '[DONE]') return
          aiMessage.content += data
        },
        onDone: () => {
          connectionStatus.value = CONNECTION_STATUS.IDLE
          aiMessage.status = CONNECTION_STATUS.IDLE
          cancelSSE = null
        },
        onError: (err) => {
          if (err.name !== 'AbortError') {
            aiMessage.content = '读取流失败: ' + err.message
            aiMessage.status = CONNECTION_STATUS.ERROR
            aiMessage.type = 'system'
            connectionStatus.value = CONNECTION_STATUS.ERROR
          }
          cancelSSE = null
        }
      })
    }).catch(err => {
      if (err.name !== 'AbortError') {
        aiMessage.content = '请求失败: ' + err.message
        aiMessage.status = CONNECTION_STATUS.ERROR
        aiMessage.type = 'system'
        connectionStatus.value = CONNECTION_STATUS.ERROR
      }
    })
  } else {
    // 纯文本：GET + EventSource
    eventSource = chatWithLoveApp(trimmedMessage, chatId.value)

    eventSource.onmessage = (event) => {
      const data = event.data
      if (data === '[DONE]') {
        connectionStatus.value = CONNECTION_STATUS.IDLE
        aiMessage.status = CONNECTION_STATUS.IDLE
        closeSseConnection(eventSource)
        eventSource = null
        return
      }
      if (!data) return
      connectionStatus.value = CONNECTION_STATUS.STREAMING
      aiMessage.status = CONNECTION_STATUS.STREAMING
      aiMessage.content += data
    }

    eventSource.onerror = () => {
      handleStreamError(aiMessage.id)
    }
  }
}

const retryLastMessage = () => {
  if (!lastSubmittedMessage.value) return
  sendMessage(lastSubmittedMessage.value, lastSubmittedImage.value)
}

const goBack = () => {
  router.push('/')
}

onMounted(() => {
  chatId.value = generateChatId('love')

  addMessage('欢迎来到 AI 恋爱大师。你可以直接描述困惑、关系背景和你的目标，我会用更贴近现实沟通的方式陪你分析。', {
    isUser: false,
    type: 'welcome'
  })
})

onBeforeUnmount(() => {
  cancelStream()
})
</script>

<style scoped>
.chat-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 1.25rem clamp(0.9rem, 3vw, 2rem) 1.5rem;
}

.page-content {
  flex: 1;
  width: min(1100px, 100%);
  margin: 0 auto;
  display: flex;
}

.love-master-page {
  background:
    radial-gradient(circle at top left, rgba(244, 114, 182, 0.18), transparent 28%),
    linear-gradient(180deg, #fff7fb 0%, #fff1f5 55%, #fff7fb 100%);
}
</style>
