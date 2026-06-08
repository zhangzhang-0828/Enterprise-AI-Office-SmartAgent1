<template>
  <div class="chat-shell">
    <header v-if="title || subtitle || statusLabel || canGoBack" class="chat-header">
      <button v-if="canGoBack" type="button" class="back-button" @click="$emit('go-back')">返回</button>
      <div class="chat-header-copy">
        <p v-if="eyebrow" class="chat-eyebrow">{{ eyebrow }}</p>
        <h1 v-if="title" class="chat-title">{{ title }}</h1>
        <p v-if="subtitle" class="chat-subtitle">{{ subtitle }}</p>
      </div>
      <div class="chat-status" :class="`is-${normalizedStatus}`">
        <span class="status-dot"></span>
        <span>{{ statusLabel }}</span>
      </div>
    </header>

    <div class="chat-card">
      <div class="chat-messages" ref="messagesContainer">
        <div v-for="msg in messages" :key="msg.id" class="message-wrapper" :class="{ 'is-user': msg.isUser }">
          <div v-if="!msg.isUser" class="message ai-message" :class="[msg.type, `is-${msg.status || 'idle'}`]">
            <div class="avatar ai-avatar">
              <AiAvatarFallback :type="aiType" />
            </div>
            <div class="message-body">
              <div class="message-bubble">
                <div class="message-content">{{ msg.content }}</div>
                <span
                  v-if="showTypingIndicator(msg)"
                  class="typing-indicator"
                  aria-hidden="true"
                >▋</span>
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
        <button
          v-if="isBusy"
          type="button"
          class="toolbar-button secondary"
          @click="$emit('cancel')"
        >
          停止生成
        </button>
        <button
          v-if="canRetry"
          type="button"
          class="toolbar-button"
          @click="$emit('retry')"
        >
          重新发送
        </button>
      </div>

      <div class="chat-input-container">
        <div class="chat-input">
          <textarea
            ref="inputRef"
            v-model="inputMessage"
            class="input-box"
            :placeholder="placeholder"
            :disabled="isBusy"
            rows="1"
            @input="resizeTextarea"
            @keydown="handleKeydown"
          ></textarea>
          <button
            type="button"
            class="send-button"
            :disabled="isBusy || !trimmedMessage"
            @click="handleSend"
          >
            {{ isBusy ? '生成中...' : '发送' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import AiAvatarFallback from './AiAvatarFallback.vue'
import { CONNECTION_STATUS } from '../utils/chat'

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  connectionStatus: {
    type: String,
    default: CONNECTION_STATUS.IDLE
  },
  aiType: {
    type: String,
    default: 'default'
  },
  title: {
    type: String,
    default: ''
  },
  subtitle: {
    type: String,
    default: ''
  },
  eyebrow: {
    type: String,
    default: ''
  },
  statusLabel: {
    type: String,
    default: '准备就绪'
  },
  placeholder: {
    type: String,
    default: '请输入消息...'
  },
  canRetry: {
    type: Boolean,
    default: false
  },
  canGoBack: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['send-message', 'cancel', 'retry', 'go-back'])

const inputMessage = ref('')
const messagesContainer = ref(null)
const inputRef = ref(null)

const trimmedMessage = computed(() => inputMessage.value.trim())
const normalizedStatus = computed(() => props.connectionStatus || CONNECTION_STATUS.IDLE)
const isBusy = computed(() => normalizedStatus.value === CONNECTION_STATUS.CONNECTING || normalizedStatus.value === CONNECTION_STATUS.STREAMING)

const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
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

const handleSend = async () => {
  if (!trimmedMessage.value || isBusy.value) return

  emit('send-message', trimmedMessage.value)
  inputMessage.value = ''
  await resetTextarea()
}

const handleKeydown = (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

const showTypingIndicator = (message) => {
  if (!message || message.isUser) return false
  if (!isBusy.value) return false
  return message.id === props.messages[props.messages.length - 1]?.id
}

watch(() => props.messages.length, scrollToBottom)
watch(() => props.messages.map(message => `${message.id}:${message.content}`).join('|'), scrollToBottom)
watch(inputMessage, resizeTextarea)

onMounted(() => {
  scrollToBottom()
  resizeTextarea()
})
</script>

<style scoped>
.chat-shell {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-height: 100%;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.chat-header-copy {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.chat-eyebrow {
  color: #6366f1;
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

.back-button,
.toolbar-button,
.send-button {
  border: none;
}

.back-button,
.toolbar-button.secondary {
  background: rgba(99, 102, 241, 0.08);
  color: #4338ca;
}

.back-button {
  padding: 0.75rem 1rem;
  border-radius: 999px;
  font-weight: 600;
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
    radial-gradient(circle at top right, rgba(99, 102, 241, 0.08), transparent 30%),
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

.toolbar-button {
  padding: 0.6rem 0.95rem;
  border-radius: 999px;
  background: rgba(79, 70, 229, 0.12);
  color: #4338ca;
  font-weight: 600;
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
}

.input-box::placeholder {
  color: #94a3b8;
}

.send-button {
  height: 3rem;
  min-width: 6.5rem;
  padding: 0 1.25rem;
  border-radius: 16px;
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  color: #fff;
  font-weight: 700;
  box-shadow: 0 16px 32px rgba(79, 70, 229, 0.22);
  transition: transform 0.2s ease, box-shadow 0.2s ease, opacity 0.2s ease;
}

.send-button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 18px 34px rgba(79, 70, 229, 0.28);
}

.send-button:disabled,
.toolbar-button:disabled,
.back-button:disabled,
.input-box:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.typing-indicator {
  display: inline-block;
  margin-left: 0.25rem;
  color: #6366f1;
  animation: blink 0.8s infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 0.2;
  }
  50% {
    opacity: 1;
  }
}

@media (max-width: 768px) {
  .chat-header {
    align-items: flex-start;
  }

  .chat-status {
    width: 100%;
    justify-content: center;
  }

  .chat-messages {
    min-height: 300px;
    max-height: none;
    padding: 1rem;
  }

  .message {
    max-width: 100%;
  }

  .chat-input {
    flex-direction: column;
    align-items: stretch;
  }

  .send-button {
    width: 100%;
  }
}

@media (max-width: 480px) {
  .chat-shell {
    gap: 0.8rem;
  }

  .chat-title {
    font-size: 1.25rem;
  }

  .chat-subtitle {
    font-size: 0.92rem;
  }

  .message-bubble {
    padding: 0.85rem 0.9rem;
  }

  .chat-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
