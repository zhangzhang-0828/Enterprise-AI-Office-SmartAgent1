<template>
  <div class="page-shell home-page">
    <section class="hero">
      <div class="hero-copy">
        <p class="hero-eyebrow">AI 应用平台</p>
        <h1 class="hero-title">kyrieAI 超级智能体</h1>
        <p class="hero-subtitle">
          在一个更清爽的工作台中体验 AI 恋爱顾问与超级智能体，让对话、任务规划和灵感探索都更自然。
        </p>
      </div>

      <div class="hero-badges">
        <span class="badge">SSE 流式响应</span>
        <span class="badge">多轮对话</span>
        <span class="badge">智能体任务规划</span>
      </div>
    </section>

    <section class="apps-grid" aria-label="应用列表">
      <article class="app-card card-love" @click="navigateTo('/love-master')">
        <div class="card-icon" aria-hidden="true">❤️</div>
        <div class="card-copy">
          <h2>AI 恋爱大师</h2>
          <p>适合情感倾诉、关系沟通与建议整理，回复更贴近日常聊天体验。</p>
        </div>
        <div class="card-meta">
          <span>多轮陪伴</span>
          <span>实时回复</span>
        </div>
        <button class="card-action" type="button">立即体验</button>
      </article>

      <article class="app-card card-agent" @click="navigateTo('/super-agent')">
        <div class="card-icon" aria-hidden="true">🤖</div>
        <div class="card-copy">
          <h2>AI 超级智能体</h2>
          <p>适合复杂问题拆解、步骤化输出和任务执行过程展示。</p>
        </div>
        <div class="card-meta">
          <span>任务推理</span>
          <span>步骤输出</span>
        </div>
        <button class="card-action" type="button">开始对话</button>
      </article>

      <article class="app-card card-vision" @click="navigateTo('/multimodal')">
        <div class="card-icon" aria-hidden="true">🖼️</div>
        <div class="card-copy">
          <h2>AI 图片理解</h2>
          <p>上传任意图片，AI 为你解读图表、分析截图、识别代码或解读梗图。</p>
        </div>
        <div class="card-meta">
          <span>多模态</span>
          <span>图文问答</span>
        </div>
        <button class="card-action" type="button">立即体验</button>
      </article>
    </section>

    <AppFooter />
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import AppFooter from '../components/AppFooter.vue'

useHead({
  title: 'kyrieAI超级智能体应用平台 - 首页',
  meta: [
    {
      name: 'description',
      content: 'kyrieAI超级智能体应用平台提供AI恋爱大师和AI超级智能体服务，满足您的各种AI对话需求'
    },
    {
      name: 'keywords',
      content: 'AI智能体,AI应用,AI恋爱大师,AI助手,智能对话,kyrie,AI超级智能体,首页'
    }
  ]
})

const router = useRouter()

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped>
.page-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 3rem;
  padding: 3rem clamp(1rem, 3vw, 2rem) 2rem;
}

.home-page {
  position: relative;
}

.hero {
  max-width: 1100px;
  margin: 0 auto;
  width: 100%;
  padding: clamp(1.5rem, 4vw, 3rem);
  border-radius: 32px;
  background:
    radial-gradient(circle at top right, rgba(59, 130, 246, 0.22), transparent 28%),
    linear-gradient(135deg, rgba(15, 23, 42, 0.96), rgba(30, 41, 59, 0.92));
  color: #fff;
  box-shadow: 0 26px 60px rgba(15, 23, 42, 0.22);
}

.hero-copy {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-width: 760px;
}

.hero-eyebrow {
  width: fit-content;
  padding: 0.45rem 0.8rem;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: #c4b5fd;
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.hero-title {
  font-size: clamp(2.2rem, 5vw, 4.5rem);
  line-height: 1.05;
  letter-spacing: -0.03em;
}

.hero-subtitle {
  color: rgba(255, 255, 255, 0.78);
  font-size: clamp(1rem, 2vw, 1.15rem);
  line-height: 1.8;
}

.hero-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1.75rem;
}

.badge {
  padding: 0.65rem 0.95rem;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.86);
  font-size: 0.92rem;
}

.apps-grid {
  width: 100%;
  max-width: 1100px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1.5rem;
}

.app-card {
  display: flex;
  flex-direction: column;
  gap: 1.1rem;
  padding: 1.6rem;
  border-radius: 28px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 24px 50px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(18px);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  cursor: pointer;
}

.app-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 28px 54px rgba(15, 23, 42, 0.12);
}

.card-icon {
  width: 3.5rem;
  height: 3.5rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 20px;
  font-size: 1.75rem;
}

.card-love .card-icon {
  background: linear-gradient(135deg, rgba(244, 114, 182, 0.18), rgba(251, 146, 60, 0.22));
}

.card-agent .card-icon {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.18), rgba(99, 102, 241, 0.22));
}

.card-vision .card-icon {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.18), rgba(168, 85, 247, 0.22));
}

.card-vision .card-meta span {
  background: rgba(139, 92, 246, 0.08);
  color: #7c3aed;
}

.card-copy h2 {
  font-size: 1.35rem;
  color: #0f172a;
  margin-bottom: 0.4rem;
}

.card-copy p {
  color: #64748b;
  line-height: 1.7;
}

.card-meta {
  display: flex;
  gap: 0.6rem;
  flex-wrap: wrap;
}

.card-meta span {
  padding: 0.45rem 0.7rem;
  border-radius: 999px;
  background: rgba(99, 102, 241, 0.08);
  color: #4f46e5;
  font-size: 0.85rem;
  font-weight: 600;
}

.card-action {
  margin-top: auto;
  align-self: flex-start;
  padding: 0.85rem 1.15rem;
  border-radius: 14px;
  border: none;
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  color: #fff;
  font-weight: 700;
  box-shadow: 0 18px 32px rgba(79, 70, 229, 0.22);
}

@media (max-width: 768px) {
  .page-shell {
    gap: 2rem;
    padding-top: 1.5rem;
  }

  .apps-grid {
    grid-template-columns: 1fr;
  }
}
</style>
