<template>
  <div class="chat-page super-agent-page">
    <div class="page-content">
      <ChatRoom
        :messages="messages"
        :connection-status="connectionStatus"
        :status-label="statusLabel"
        :can-retry="canRetry"
        :can-go-back="true"
        ai-type="super"
        title="AI 超级智能体"
        eyebrow="任务规划"
        subtitle="更适合复杂问题拆解、连续步骤输出与任务式交流。"
        placeholder="告诉我你的目标，我来帮你逐步拆解..."
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
import { chatWithManusWithImage } from '../api'
import {
  CONNECTION_STATUS,
  createMessage,
  createSSEParser
} from '../utils/chat'

useHead({
  title: 'AI超级智能体 - kyrieAI超级智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'AI超级智能体是kyrieAI超级智能体应用平台的全能助手，能解答各类专业问题，提供精准建议和解决方案'
    },
    {
      name: 'keywords',
      content: 'AI超级智能体,智能助手,专业问答,AI问答,专业建议,kyrie,AI智能体'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const connectionStatus = ref(CONNECTION_STATUS.IDLE)
const lastSubmittedMessage = ref('')
const lastSubmittedImage = ref(null)
let cancelSSE = null

const statusLabel = computed(() => {
  switch (connectionStatus.value) {
    case CONNECTION_STATUS.CONNECTING:
      return '正在建立连接'
    case CONNECTION_STATUS.STREAMING:
      return '正在逐步输出'
    case CONNECTION_STATUS.ERROR:
      return '执行中断'
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

const cancelStream = () => {
  if (cancelSSE) {
    cancelSSE()
    cancelSSE = null
  }
  connectionStatus.value = CONNECTION_STATUS.IDLE
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
    const { promise, controller } = chatWithManusWithImage(trimmedMessage, imageFile)
    cancelSSE = null

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
          aiMessage.content += (aiMessage.content ? '\n' : '') + data
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
    const { promise, controller } = chatWithManusWithImage(trimmedMessage, null)
    cancelSSE = () => controller.abort()

    promise.then(async response => {
      const reader = response.getReader()
      const decoder = new TextDecoder()
      connectionStatus.value = CONNECTION_STATUS.STREAMING
      aiMessage.status = CONNECTION_STATUS.STREAMING
      createSSEParser(reader, decoder, {
        onChunk: (text) => {
          if (text === '[DONE]') return
          aiMessage.content += (aiMessage.content ? '\n' : '') + text
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
      cancelSSE = null
    })
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
  addMessage('你好，我是 AI 超级智能体。你可以把任务目标、限制条件和预期结果告诉我，我会尽量分步骤给出可执行建议。', {
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

.super-agent-page {
  background:
    radial-gradient(circle at top right, rgba(59, 130, 246, 0.18), transparent 28%),
    linear-gradient(180deg, #f6f9ff 0%, #eef4ff 55%, #f8fbff 100%);
}
</style>
