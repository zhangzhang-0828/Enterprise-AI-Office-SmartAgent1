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
import { chatWithManus } from '../api'
import {
  buildErrorMessage,
  closeSseConnection,
  CONNECTION_STATUS,
  createMessage
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
let eventSource = null

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
  if (!eventSource) return

  closeSseConnection(eventSource)
  eventSource = null
  connectionStatus.value = CONNECTION_STATUS.IDLE
}

const sendMessage = (message) => {
  const trimmedMessage = message.trim()
  if (!trimmedMessage) return

  cancelStream()

  lastSubmittedMessage.value = trimmedMessage
  addMessage(trimmedMessage, {
    isUser: true,
    type: 'user-question'
  })

  const aiMessage = addMessage('', {
    isUser: false,
    type: 'ai-answer',
    status: CONNECTION_STATUS.CONNECTING
  })

  connectionStatus.value = CONNECTION_STATUS.CONNECTING
  eventSource = chatWithManus(trimmedMessage)

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
    aiMessage.content += `${aiMessage.content ? '\n' : ''}${data}`
  }

  eventSource.onerror = () => {
    closeSseConnection(eventSource)
    eventSource = null
    connectionStatus.value = CONNECTION_STATUS.ERROR
    aiMessage.status = CONNECTION_STATUS.ERROR

    if (!aiMessage.content.trim()) {
      aiMessage.content = buildErrorMessage('执行过程中连接中断，请重新发送任务。').content
      aiMessage.type = 'system'
    }
  }
}

const retryLastMessage = () => {
  if (!lastSubmittedMessage.value) return
  sendMessage(lastSubmittedMessage.value)
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
