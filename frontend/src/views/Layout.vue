<template>
  <div class="app-layout">
    <!-- SaaS Top Navigation Bar -->
    <div class="top-navbar">
      <div class="navbar-left">
        <span class="navbar-logo">✈️ EDG-AXIS</span>
        <span class="navbar-subtitle">无人机边缘计算集群核心控制台</span>
      </div>
      <div class="navbar-right">
        <span class="sys-time">{{ currentTime }}</span>
        <span class="navbar-user-badge" @click="openProfileDialog" style="cursor: pointer;" title="点击修改个人档案">
          <span class="user-dot"></span>
          {{ appStore.user?.username || 'GUEST' }}
          <span class="user-coords">[{{ appStore.user?.x || 50 }}, {{ appStore.user?.y || 50 }}]</span>
        </span>
        <el-button size="small" type="danger" plain @click="handleLogout" style="border-color: #30363d; background: transparent; color: #ff4949">脱离网格</el-button>
      </div>
    </div>

    <div class="layout-body">
      <!-- Sidebar Menu -->
      <div class="sidebar">
        <el-menu
          :default-active="activePath"
          class="cyber-menu"
          router
          background-color="#f5f5f5"
          text-color="#555555"
          active-text-color="#1a1a1a"
        >
          <el-menu-item index="/">
            <el-icon><Odometer /></el-icon>
            <span>系统控制台</span>
          </el-menu-item>
          <el-menu-item index="/cluster">
            <el-icon><Platform /></el-icon>
            <span>边缘网格信标</span>
          </el-menu-item>
          <el-menu-item index="/analytics">
            <el-icon><DataAnalysis /></el-icon>
            <span>多维效能基准</span>
          </el-menu-item>
          <el-menu-item index="/algorithm-compare">
            <el-icon><Guide /></el-icon>
            <span>全算法对比</span>
          </el-menu-item>
          <el-menu-item index="/trace">
            <el-icon><List /></el-icon>
            <span>任务生命周期审计</span>
          </el-menu-item>
          <el-menu-item v-if="appStore.user?.role === 'ADMIN'" index="/users">
            <el-icon><User /></el-icon>
            <span>权限与用户管理</span>
          </el-menu-item>
        </el-menu>

        <div class="ws-status-panel">
           <div class="ws-status-indicator" :class="{ 'is-connected': appStore.stompClient?.connected }">
              <span class="ws-dot"></span>
              {{ appStore.stompClient?.connected ? 'WS: CONNECTED' : 'WS: OFFLINE' }}
           </div>
        </div>
      </div>
      
      <!-- Main Content Area -->
      <div class="content-area">
         <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <keep-alive>
                <component :is="Component" />
              </keep-alive>
            </transition>
         </router-view>
      </div>
    </div>

    <!-- 档案设置弹窗 -->
    <el-dialog v-model="profileDialogVisible" title="操作员档案设置 / PROFILE" width="400px" custom-class="cyber-dialog">
      <el-form :model="profileForm" label-width="100px" class="cyber-form">
        <el-form-item label="操作员名称">
          <el-input v-model="profileForm.username" />
        </el-form-item>
        <el-form-item label="经度坐标 (X)">
          <el-input-number v-model="profileForm.x" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="纬度坐标 (Y)">
          <el-input-number v-model="profileForm.y" :min="0" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="profileDialogVisible = false">取消</el-button>
          <el-button type="primary" class="cyber-btn" @click="saveProfile" :loading="savingProfile">
            更新档案
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '../store/appStore'
import { Odometer, Platform, DataAnalysis, List, User, Guide } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const route = useRoute()
const appStore = useAppStore()

const activePath = computed(() => route.path)

const currentTime = ref('')
let timeInterval: any = null

const updateClock = () => {
    const d = new Date()
    currentTime.value = `${d.getFullYear()}/${(d.getMonth()+1).toString().padStart(2,'0')}/${d.getDate().toString().padStart(2,'0')} ${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}:${d.getSeconds().toString().padStart(2,'0')}`
}

onMounted(() => {
    updateClock()
    timeInterval = setInterval(updateClock, 1000)
})

onUnmounted(() => {
    if (timeInterval) clearInterval(timeInterval)
})

const profileDialogVisible = ref(false)
const profileForm = ref({ username: '', x: 50, y: 50 })
const savingProfile = ref(false)

const openProfileDialog = () => {
  if (!appStore.user) return
  profileForm.value.username = appStore.user.username
  profileForm.value.x = appStore.user.x
  profileForm.value.y = appStore.user.y
  profileDialogVisible.value = true
}

const saveProfile = async () => {
  if (!profileForm.value.username) {
    ElMessage.warning('代号不能为空')
    return
  }
  savingProfile.value = true
  try {
    const success = await appStore.updateUser(profileForm.value.username, profileForm.value.x, profileForm.value.y)
    if (success) {
      ElMessage.success('通信档案已更新，坐标重定向完成')
      profileDialogVisible.value = false
    } else {
      ElMessage.error('更新失败：可能用户名已存在或网络异常')
    }
  } catch (e) {
    ElMessage.error('更新操作异常')
  } finally {
    savingProfile.value = false
  }
}

const handleLogout = () => {
    appStore.logout()
    window.location.href = '/login'
}
</script>

<style scoped>
.app-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #ffffff;
  color: #222222;
}

/* Top Navigation Bar */
.top-navbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  height: 50px;
  background: #f8f8f8;
  border-bottom: 1px solid #dddddd;
  flex-shrink: 0;
}

.navbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.navbar-logo {
  font-size: 16px;
  font-weight: 800;
  color: #1a1a1a;
  letter-spacing: 2px;
}

.navbar-subtitle {
  font-size: 11px;
  color: #666666;
  letter-spacing: 1px;
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 15px;
}

.sys-time {
  font-family: var(--font-mono);
  font-size: 13px;
  color: #333333;
  letter-spacing: 1px;
  margin-right: 10px;
}

.navbar-user-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(0, 0, 0, 0.05);
  border: 1px solid #cccccc;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 12px;
  color: #333333;
  font-weight: 500;
}

.user-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #27ae60;
}

@keyframes dotPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.layout-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.sidebar {
  width: 220px;
  background: #f5f5f5;
  border-right: 1px solid #dddddd;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.cyber-menu {
  border-right: none;
  flex: 1;
}
.cyber-menu .el-menu-item {
   font-weight: 500;
}
.cyber-menu .el-menu-item.is-active {
    background: rgba(0, 0, 0, 0.08) !important;
    color: #1a1a1a;
    border-right: 3px solid #333333;
}

.ws-status-panel {
  padding: 15px;
  border-top: 1px solid #dddddd;
}

.ws-status-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: #666666;
  letter-spacing: 1px;
}

.ws-status-indicator.is-connected {
  color: #27ae60;
}

.ws-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #cccccc;
}

.ws-status-indicator.is-connected .ws-dot {
  background: #27ae60;
}

.content-area {
  flex: 1;
  padding: 16px;
  overflow-y: auto;
  overflow-x: hidden;
  background: #ffffff;
}

@media print {
  .sidebar, .ws-status-panel, .top-navbar {
    background: #f5f5f5 !important;
    border-color: #dddddd !important;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }
  .app-layout {
    background: #ffffff !important;
    color: #111111 !important;
  }
  .navbar-logo { color: #111111 !important; }
  .navbar-subtitle, .sys-time { color: #555555 !important; }
  .content-area { background: #ffffff !important; }
}

/* Page Transition */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>
