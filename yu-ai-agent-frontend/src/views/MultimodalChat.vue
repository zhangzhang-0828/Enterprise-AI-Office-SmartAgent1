<template>
  <div class="chat-page multimodal-page">
    <div class="page-content">
      <div class="multimodal-chat-wrapper">
        <header class="chat-header">
          <button v-if="canGoBack" type="button" class="back-button" @click="$emit('go-back')">返回</button>
          <div class="chat-header-copy">
            <p class="chat-eyebrow">多模态对话</p>
            <h1 class="chat-title">AI 图片理解助手</h1>
            <p class="chat-subtitle">上传图片，让 AI 为你解读、分析或问答</p>
          </div>
          <div class="chat-status" :class="`is-${connectionStatus}`">
            <span class="status-dot"></span>
            <span>{{ statusLabel }}</span>
          </div>
        </header>

        <div class="chat-card">
          <div class="chat-messages" ref="messagesContainer">
            <div v-for="msg in messages" :key="msg.id" class="message-wrapper" :class="{ 'is-user': msg.isUser }">
              <div v-if="!msg.isUser" class="message ai-message" :class="[msg.type, `is-${msg.status || 'idle'}`]">
                <div class="avatar ai-avatar">
                  <div class="avatar-icon">🖼️</div>
                </div>
                <div class="message-body">
                  <div class="message-bubble">
                    <img v-if="msg.imageUrl" :src="msg.imageUrl" class="message-image" alt="用户上传的图片" />
                    <div class="message-content">{{ msg.content }}</div>
                    <span v-if="showTypingIndicator(msg)" class="typing-indicator" aria-hidden="true">▋</span>
                  </div>
                  <div class="message-meta">
                    <span class="message-time">{{ formatTime(msg.time) }}</span>
                    <span v-if="msg.type === 'system'" class="message-tag">系统提示</span>
                  </div>
                </div>
              </div>

              <div v-else class="message user-message" :class="[msg.type]">
                <div class="message-body">
                  <div class="message-bubble">
                    <img v-if="msg.imageUrl" :src="msg.imageUrl" class="message-image" alt="我上传的图片" />
                    <div class="message-content">{{ msg.content }}</div>
                  </div>
                  <div class="message-meta">
                    <span class="message-time">{{ formatTime(msg.time) }}</span>
                  </div>
                </div>
                <div class="avatar user-avatar">
                  <div class="avatar-placeholder">我</div>
                </div>
              </div>
            </div>
          </div>

          <div class="chat-toolbar">
            <p class="toolbar-hint">Enter 发送，Shift + Enter 换行</p>
            <div class="toolbar-actions">
              <button
                v-if="isBusy"
                type="button"
                class="toolbar-button secondary"
                @click="cancelStream"
              >
                停止生成
              </button>
              <button
                v-if="canRetry"
                type="button"
                class="toolbar-button"
                @click="retryLastMessage"
              >
                重新发送
              </button>
            </div>
          </div>

          <div class="chat-input-container">
            <div class="chat-input">
              <div class="input-toolbar">
                <label class="image-upload-btn" :class="{ 'has-image': previewImage }" title="上传图片">
                  <input
                    type="file"
                    accept="image/jpeg,image/png,image/gif,image/webp"
                    class="file-input"
                    @change="handleImageSelect"
                  />
                  <span class="upload-icon">{{ previewImage ? '🖼️' : '📷' }}</span>
                  <span v-if="previewImage" class="clear-image" @click.stop="clearImage">✕</span>
                </label>
                <div v-if="previewImage" class="image-preview-wrapper">
                  <img :src="previewImage" class="image-preview" alt="预览" />
                </div>
              </div>
              <textarea
                ref="inputRef"
                v-model="inputMessage"
                class="input-box"
                placeholder="描述你想问的问题..."
                :disabled="isBusy"
                rows="1"
                @input="resizeTextarea"
                @keydown="handleKeydown"
              ></textarea>
              <button
                type="button"
                class="send-button"
                :disabled="isBusy || !canSend"
                @click="handleSend"
              >
                {{ isBusy ? '生成中...' : '发送' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <AppFooter />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppFooter from '../components/AppFooter.vue'
import { createMultimodalStream } from '../api'
import { createMessage, generateChatId } from '../utils/chat'

const props = defineProps({
  canGoBack: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['go-back'])

const router = useRouter()
const messages = ref([])
const connectionStatus = ref('idle')
const lastSubmittedMessage = ref('')
const lastSubmittedImage = ref(null)
const inputMessage = ref('')
const inputRef = ref(null)
const messagesContainer = ref(null)
const selectedImageFile = ref(null)
const previewImage = ref('')
let abortController = null

const trimmedMessage = computed(() => inputMessage.value.trim())
const isBusy = computed(() => connectionStatus.value === 'connecting' || connectionStatus.value === 'streaming')
const canSend = computed(() => trimmedMessage.value || selectedImageFile.value)
const canRetry = computed(() => Boolean(lastSubmittedMessage.value) && connectionStatus.value === 'error')

const statusLabel = computed(() => {
  switch (connectionStatus.value) {
    case 'connecting': return '正在建立连接'
    case 'streaming': return '正在回复'
    case 'error': return '连接异常'
    default: return '准备就绪'
  }
})

const formatTime = (timestamp) => {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

const resizeTextarea = () => {
  if (!inputRef.value) return
  inputRef.value.style.height = 'auto'
  inputRef.value.style.height = `${Math.min(inputRef.value.scrollHeight, 132)}px`
}

const resetTextarea = async () => {
  await nextTick()
  if (inputRef.value) {
    inputRef.value.style.height = 'auto'
  }
}

const handleImageSelect = (event) => {
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    alert('图片大小不能超过 10MB')
    event.target.value = ''
    return
  }
  selectedImageFile.value = file
  previewImage.value = URL.createObjectURL(file)
}

const clearImage = () => {
  selectedImageFile.value = null
  previewImage.value = ''
}

const showTypingIndicator = (message) => {
  if (!message || message.isUser) return false
  if (!isBusy.value) return false
  return message.id === messages.value[messages.value.length - 1]?.id
}

const addMessage = (content, overrides = {}) => {
  const message = createMessage(content, overrides)
  messages.value.push(message)
  return message
}

const cancelStream = () => {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  connectionStatus.value = 'idle'
}

const sendMessage = (messageText, imageFile = null) => {
  const trimmed = messageText.trim()
  if (!trimmed && !imageFile) return

  cancelStream()

  lastSubmittedMessage.value = trimmed
  lastSubmittedImage.value = imageFile

  // 添加用户消息
  addMessage(trimmed, {
    isUser: true,
    type: 'user-question',
    imageUrl: previewImage.value || null
  })

  // 添加 AI 消息占位
  const aiMessage = addMessage('', {
    isUser: false,
    type: 'ai-answer',
    status: 'connecting'
  })

  connectionStatus.value = 'connecting'
  const { promise, controller } = createMultimodalStream(trimmed, imageFile)
  abortController = controller

  promise.then(async (body) => {
    if (!body) {
      aiMessage.content = '服务器返回了空响应'
      aiMessage.status = 'error'
      aiMessage.type = 'system'
      connectionStatus.value = 'error'
      return
    }
    const reader = body.getReader()
    const decoder = new TextDecoder()

    const read = () => {
      reader.read().then(({ done, value }) => {
        if (done) {
          connectionStatus.value = 'idle'
          aiMessage.status = 'idle'
          return
        }
        const chunk = decoder.decode(value)
        connectionStatus.value = 'streaming'
        aiMessage.status = 'streaming'

        // 处理 SSE 数据格式：data: xxx\n\n
        const lines = chunk.split('\n')
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data && data !== '[DONE]') {
              aiMessage.content += data
            }
          }
        }
        read()
      }).catch(err => {
        if (err.name !== 'AbortError') {
          aiMessage.content = '读取流失败: ' + err.message
          aiMessage.status = 'error'
          aiMessage.type = 'system'
          connectionStatus.value = 'error'
        }
      })
    }
    read()
  }).catch(err => {
    if (err.name !== 'AbortError') {
      aiMessage.content = '请求失败: ' + err.message
      aiMessage.status = 'error'
      aiMessage.type = 'system'
      connectionStatus.value = 'error'
    }
  })
}

const handleSend = async () => {
  if (isBusy.value || !canSend.value) return
  const msg = trimmedMessage.value
  const img = selectedImageFile.value
  inputMessage.value = ''
  await resetTextarea()
  clearImage()
  sendMessage(msg, img)
}

const handleKeydown = (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

const retryLastMessage = () => {
  if (!lastSubmittedMessage.value) return
  sendMessage(lastSubmittedMessage.value, lastSubmittedImage.value)
}

watch(() => messages.value.length, scrollToBottom)
watch(() => messages.value.map(m => `${m.id}:${m.content}`).join('|'), scrollToBottom)

onMounted(() => {
  scrollToBottom()
  resizeTextarea()
  addMessage('你好！我是 AI 图片理解助手。你可以上传任意图片，并针对图片内容向我提问，比如描述图片、分析图表、解读代码截图等。', {
    isUser: false,
    type: 'welcome'
  })
})

onBeforeUnmount(() => {
  cancelStream()
})
</script>

<style scoped>
.multimodal-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 1.25rem clamp(0.9rem, 3vw, 2rem) 1.5rem;
  background:
    radial-gradient(circle at top right, rgba(139, 92, 246, 0.15), transparent 28%),
    linear-gradient(180deg, #f5f3ff 0%, #ede9fe 55%, #f8f5ff 100%);
}

.page-content {
  flex: 1;
  width: min(1100px, 100%);
  margin: 0 auto;
  display: flex;
}

.multimodal-chat-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.back-button {
  padding: 0.75rem 1rem;
  border-radius: 999px;
  font-weight: 600;
  border: none;
  background: rgba(99, 102, 241, 0.08);
  color: #4338ca;
  cursor: pointer;
}

.chat-header-copy {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.chat-eyebrow {
  color: #7c3aed;
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.chat-title {
  font-size: clamp(1.4rem, 2vw, 2rem);
  font-weight: 700;
  color: #0f172a;
}

.chat-subtitle {
  color: #64748b;
  line-height: 1.6;
  max-width: 60ch;
}

.chat-status {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.7rem 0.9rem;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(148, 163, 184, 0.24);
  box-shadow: 0 10px 25px rgba(15, 23, 42, 0.06);
  color: #475569;
  font-size: 0.92rem;
  font-weight: 600;
}

.status-dot {
  width: 0.65rem;
  height: 0.65rem;
  border-radius: 999px;
  background: #22c55e;
  box-shadow: 0 0 0 6px rgba(34, 197, 94, 0.12);
}

.chat-status.is-connecting .status-dot,
.chat-status.is-streaming .status-dot {
  background: #f59e0b;
  box-shadow: 0 0 0 6px rgba(245, 158, 11, 0.12);
}

.chat-status.is-error .status-dot {
  background: #ef4444;
  box-shadow: 0 0 0 6px rgba(239, 68, 68, 0.12);
}

.chat-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-radius: 28px;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(255, 255, 255, 0.78);
  box-shadow:
    0 24px 60px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(18px);
}

.chat-messages {
  flex: 1;
  min-height: 340px;
  max-height: min(72vh, 880px);
  overflow-y: auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  background:
    radial-gradient(circle at top right, rgba(139, 92, 246, 0.06), transparent 30%),
    linear-gradient(180deg, rgba(248, 250, 252, 0.94), rgba(241, 245, 249, 0.85));
}

.message-wrapper {
  display: flex;
  width: 100%;
}

.message-wrapper.is-user {
  justify-content: flex-end;
}

.message {
  display: flex;
  align-items: flex-end;
  gap: 0.75rem;
  max-width: min(90%, 760px);
}

.user-message {
  flex-direction: row-reverse;
}

.message-body {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.avatar {
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 999px;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.12);
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: white;
  font-weight: 700;
}

.avatar-icon {
  font-size: 1.2rem;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #7c3aed, #a855f7);
}

.message-bubble {
  position: relative;
  border-radius: 20px;
  padding: 0.95rem 1rem;
  word-break: break-word;
  line-height: 1.75;
}

.user-message .message-bubble {
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  color: #fff;
  border-bottom-right-radius: 8px;
  box-shadow: 0 20px 28px rgba(99, 102, 241, 0.2);
}

.ai-message .message-bubble {
  background: #fff;
  color: #1f2937;
  border-bottom-left-radius: 8px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 20px 28px rgba(15, 23, 42, 0.06);
}

.ai-message.system .message-bubble,
.ai-message.is-error .message-bubble {
  background: rgba(254, 242, 242, 0.95);
  color: #b91c1c;
  border-color: rgba(248, 113, 113, 0.18);
}

.message-image {
  max-width: 280px;
  max-height: 200px;
  border-radius: 12px;
  margin-bottom: 0.5rem;
  object-fit: cover;
  display: block;
}

.user-message .message-image {
  border: 2px solid rgba(255, 255, 255, 0.3);
}

.ai-message .message-image {
  border: 1px solid rgba(148, 163, 184, 0.2);
}

.message-content {
  white-space: pre-wrap;
  font-size: 0.98rem;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0 0.2rem;
  color: #94a3b8;
  font-size: 0.76rem;
}

.user-message .message-meta {
  justify-content: flex-end;
}

.message-tag {
  display: inline-flex;
  align-items: center;
  padding: 0.15rem 0.45rem;
  border-radius: 999px;
  background: rgba(248, 113, 113, 0.12);
  color: #dc2626;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
  padding: 0.95rem 1.25rem 0;
  color: #64748b;
  font-size: 0.85rem;
}

.toolbar-hint {
  line-height: 1.5;
}

.toolbar-actions {
  display: flex;
  gap: 0.5rem;
}

.toolbar-button {
  padding: 0.6rem 0.95rem;
  border-radius: 999px;
  background: rgba(79, 70, 229, 0.12);
  color: #4338ca;
  font-weight: 600;
  border: none;
  cursor: pointer;
}

.toolbar-button.secondary {
  background: rgba(239, 68, 68, 0.08);
  color: #dc2626;
}

.chat-input-container {
  padding: 1rem 1.25rem 1.25rem;
}

.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 0.9rem;
  padding: 0.85rem;
  border-radius: 22px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
}

.input-toolbar {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
}

.image-upload-btn {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2.8rem;
  height: 2.8rem;
  border-radius: 12px;
  border: 1.5px dashed rgba(124, 58, 237, 0.35);
  background: rgba(139, 92, 246, 0.06);
  color: #7c3aed;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.image-upload-btn:hover {
  background: rgba(139, 92, 246, 0.14);
  border-color: rgba(124, 58, 237, 0.5);
}

.image-upload-btn.has-image {
  border-style: solid;
  border-color: #7c3aed;
  background: rgba(139, 92, 246, 0.12);
}

.file-input {
  display: none;
}

.upload-icon {
  font-size: 1.1rem;
  line-height: 1;
}

.clear-image {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 1.1rem;
  height: 1.1rem;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 0.6rem;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 1;
}

.image-preview-wrapper {
  display: flex;
  align-items: flex-end;
}

.image-preview {
  width: 3.5rem;
  height: 3.5rem;
  border-radius: 10px;
  object-fit: cover;
  border: 2px solid rgba(124, 58, 237, 0.3);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.input-box {
  flex: 1;
  min-height: 1.5rem;
  max-height: 132px;
  resize: none;
  border: none;
  background: transparent;
  color: #0f172a;
  line-height: 1.7;
  outline: none;
  font-family: inherit;
  font-size: 0.98rem;
}

.input-box::placeholder {
  color: #94a3b8;
}

.send-button {
  height: 3rem;
  min-width: 4.5rem;
  padding: 0 1.2rem;
  border-radius: 16px;
  border: none;
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  color: #fff;
  font-weight: 700;
  font-size: 0.95rem;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s;
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.25);
}

.send-button:hover:not(:disabled) {
  background: linear-gradient(135deg, #4338ca, #6d28d9);
  box-shadow: 0 10px 24px rgba(99, 102, 241, 0.3);
  transform: translateY(-1px);
}

.send-button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

.typing-indicator {
  display: inline-block;
  animation: blink 1s step-end infinite;
  color: #6366f1;
  margin-left: 2px;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>
