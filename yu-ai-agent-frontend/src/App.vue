<script setup>
</script>

<template>
  <router-view />
</template>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html,
body {
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 16px;
  color: #1f2937;
  background:
    radial-gradient(circle at top, rgba(99, 102, 241, 0.14), transparent 32%),
    linear-gradient(180deg, #f8fafc 0%, #eef2ff 45%, #f8fafc 100%);
  width: 100%;
  min-height: 100%;
  overflow-x: hidden;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

body {
  min-width: 320px;
}

#app {
  width: 100%;
  min-height: 100vh;
}

a {
  text-decoration: none;
  color: inherit;
}

button,
textarea,
input {
  font: inherit;
}

button {
  cursor: pointer;
}

:focus-visible {
  outline: 3px solid rgba(99, 102, 241, 0.35);
  outline-offset: 2px;
}

::selection {
  background: rgba(99, 102, 241, 0.18);
}

@media (max-width: 768px) {
  html,
  body {
    font-size: 15px;
  }
}

@media (max-width: 480px) {
  html,
  body {
    font-size: 14px;
  }
}

::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: rgba(148, 163, 184, 0.16);
  border-radius: 999px;
}

::-webkit-scrollbar-thumb {
  background: rgba(99, 102, 241, 0.45);
  border-radius: 999px;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(79, 70, 229, 0.68);
}
</style>
