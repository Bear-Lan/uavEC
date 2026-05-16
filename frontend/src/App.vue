<template>
  <el-config-provider>
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useAppStore } from './store/appStore'

const appStore = useAppStore()

onMounted(async () => {
  // 先恢复登录会话，再初始化 WebSocket（需要认证 token）
  await appStore.restoreSession()
  appStore.initWebSocket()
})
</script>

<style>
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
</style>
