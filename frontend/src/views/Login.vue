<template>
  <div class="login-container">
    <div class="cyber-grid"></div>
    <div class="radar-scan"></div>
    
    <el-card class="cyber-card login-card" shadow="always">
      <div class="login-header">
        <el-icon class="icon"><Monitor /></el-icon>
        <h2 class="typing-title">{{ isRegister ? '新终端注册_' : '终端主机接入_' }}</h2>
        <div class="sub-title">EDG-AXIS / {{ isRegister ? 'NEW HOST REGISTRATION' : 'HOST LOGIN' }}</div>
      </div>

      <el-form :model="form" class="cyber-form login-form" @keyup.enter="handleSubmit">
        <el-form-item label="操作员 ID">
          <el-input v-model="form.username" placeholder="输入终端代号" />
        </el-form-item>

        <el-form-item label="接入密钥">
          <el-input v-model="form.password" type="password" placeholder="输入接入密码" show-password />
        </el-form-item>

        <transition name="el-fade-in-linear">
          <el-form-item v-if="isRegister" label="部署坐标 [网格系 X,Y]">
            <div class="coordinate-picker">
                <el-input-number v-model="form.x" :min="0" :max="100" placeholder="X" style="width: 100px" />
                <span class="sep">/</span>
                <el-input-number v-model="form.y" :min="0" :max="100" placeholder="Y" style="width: 100px" />
            </div>
            <div class="hint">请选择您当前所在的基础设施网格位置 (0-100)，您的任何计算任务将从该节点发出。</div>
          </el-form-item>
        </transition>

        <el-button type="primary" class="cyber-btn w-full mt-4" @click="handleSubmit" :loading="logging" :disabled="!form.username || !form.password">
          {{ isRegister ? '注册并接入边缘网格' : '验证身份并接入' }}
        </el-button>

        <div class="login-footer">
          <el-link type="info" underline="never" @click="isRegister = !isRegister">
            {{ isRegister ? '已有终端授权？立即接入' : '尚未获得授权？申请物理注册' }}
          </el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../store/appStore'
import { Monitor } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const appStore = useAppStore()
const logging = ref(false)
const isRegister = ref(false)

const form = ref({
    username: '',
    password: '',
    x: Math.floor(Math.random() * 80) + 10,
    y: Math.floor(Math.random() * 80) + 10
})

const handleSubmit = async () => {
    if (!form.value.username || !form.value.password) return

    logging.value = true
    try {
        if (isRegister.value) {
            await appStore.register(form.value.username, form.value.password, form.value.x, form.value.y)
            ElMessage.success('注册成功，欢迎接入边缘网格')
        } else {
            await appStore.login(form.value.username, form.value.password)
            ElMessage.success('身份验证通过')
        }
        router.push('/')
    } catch (e: any) {
        const msg = e.response?.data?.message || (isRegister.value ? '注册失败' : '登录失败')
        ElMessage.error(msg)
        console.error(e)
    } finally {
        logging.value = false
    }
}
</script>

<style scoped>
/* Inherit existing styles... */
.login-container {
  height: 100vh;
  width: 100vw;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: #0d1117;
  position: relative;
  overflow: hidden;
}

.cyber-grid {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image: 
    linear-gradient(rgba(0, 255, 204, 0.1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 255, 204, 0.1) 1px, transparent 1px);
  background-size: 50px 50px;
  transform: perspective(500px) rotateX(60deg) translateY(-100px) translateZ(-200px);
  animation: gridMove 20s linear infinite;
  pointer-events: none;
}

@keyframes gridMove {
  0% { background-position: 0 0; }
  100% { background-position: 0 50px; }
}

.login-card {
  position: relative;
  z-index: 10;
  width: 450px;
  background: rgba(13, 17, 23, 0.85);
  backdrop-filter: blur(10px);
  border: 1px solid #30363d;
  box-shadow: 0 0 30px rgba(0, 255, 204, 0.1);
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.login-header .icon {
    font-size: 48px;
    color: #00ffcc;
    margin-bottom: 10px;
    filter: drop-shadow(0 0 10px #00ffcc);
}

.login-header h2.typing-title {
  color: #c9d1d9;
  letter-spacing: 2px;
  font-weight: 600;
  margin: 0;
  font-family: var(--font-mono);
  overflow: hidden;
  white-space: nowrap;
  animation: typing 2.5s steps(12, end), blink-caret .75s step-end infinite;
  display: inline-block;
  border-right: 2px solid #00ffcc;
  padding-right: 5px;
}

@keyframes typing {
  from { width: 0 }
  to { width: 100% }
}

@keyframes blink-caret {
  from, to { border-color: transparent }
  50% { border-color: #00ffcc; }
}

.sub-title {
    font-size: 11px;
    letter-spacing: 4px;
    color: #8b949e;
    margin-top: 5px;
    font-family: var(--font-mono);
}

.coordinate-picker {
    display: flex;
    align-items: center;
    gap: 10px;
}

.coordinate-picker .sep {
    color: #8b949e;
}

.hint {
    font-size: 12px;
    color: #8b949e;
    margin-top: 8px;
    line-height: 1.4;
}

.login-footer {
    text-align: center;
    margin-top: 20px;
}

.login-footer :deep(.el-link) {
    font-size: 12px;
    color: #8b949e;
}

.login-footer :deep(.el-link:hover) {
    color: #00ffcc;
}

.w-full { width: 100%; }
.mt-4 { margin-top: 20px; }
</style>
