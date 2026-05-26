<template>
  <div class="cluster-container">
    <el-card class="cyber-card control-card" shadow="always">
       <template #header>
          <div class="card-header cyber-header" style="justify-content: space-between;">
             <div>
                <span class="cyber-title" style="margin-right: 15px">边缘集群状态 / CLUSTER METRICS</span>
                <el-tag :type="healthStatus.type === 'normal' ? 'success' : 'danger'" effect="dark" size="small">{{ healthStatus.text }}</el-tag>
             </div>
             
             <div>
               <!-- 故障注入测试模块 -->
               <el-tooltip class="box-item" effect="dark" content="强制下线一台在线无人机模拟失效" placement="top">
                  <el-button type="danger" size="small" @click="simulateFault" :disabled="!activeNodesCount" plain>⚡ 测试故障转移</el-button>
               </el-tooltip>
               <el-tooltip class="box-item" effect="dark" content="恢复一台已下线的无人机" placement="top">
                  <el-button type="success" size="small" @click="restoreNode" :disabled="!offlineNodesCount" plain>🔄 恢复节点</el-button>
               </el-tooltip>
               <el-button type="success" size="small" @click="addDrone" :loading="addingDrone" plain style="margin-left: 10px">+ 部署新站</el-button>
             </div>
          </div>
      </template>
        
      <div v-if="appStore.nodes.length === 0" style="padding: 40px; text-align: center; color: #8b949e">
        <el-empty description="网格中暂无在线信标" />
      </div>

      <el-row :gutter="15">
         <el-col v-for="node in appStore.nodes" :key="node.id" :xs="24" :sm="12" :md="8" :lg="6" style="margin-bottom: 15px">
            <div class="cyber-uav-card" :class="{ 'offline': !node.online }">
              <div class="node-header">
                  <h3>{{ node.name }}</h3>
                  <div>
                    <el-button type="warning" size="small" circle title="热升级该节点容量" @click="openUpgradeDialog(node)" style="margin-right: 5px">⚙️</el-button>
                    <el-tag :type="node.online ? 'success' : 'danger'" effect="dark" size="small" style="margin-right: 5px">
                    {{ node.online ? 'ONLINE' : 'DOWN' }}
                    </el-tag>
                    <el-button v-if="!node.online || node.battery <= 20" type="primary" size="small" circle title="紧急定向充电 (闪充 100%)" @click="emergencyCharge(node.id)" style="margin-right: 5px">⚡</el-button>
                    <el-button type="danger" size="small" circle title="删除该台无人机" @click="deleteDrone(node.id)">❌</el-button>
                  </div>
              </div>
              
              <div class="node-stats">
                  <div class="stat-item">
                      <span class="label">平均CPU占用率</span>
                      <el-progress
                          :percentage="Math.round((node.currentCpuUsage / node.maxCpu) * 100)"
                          :status="getCpuStatus(node.currentCpuUsage, node.maxCpu)"
                          :stroke-width="12"
                      />
                      <div class="detail">{{ node.currentCpuUsage.toFixed(1) }} / {{ node.maxCpu }} Cores ({{ Math.round((node.currentCpuUsage / node.maxCpu) * 100) }}%)</div>
                  </div>
                  
                  <div class="stat-item">
                      <span class="label">MEM LOAD</span>
                      <el-progress 
                          :percentage="Math.min(100, Math.round((node.currentMemoryUsage / Math.max(1, node.maxMemory)) * 100))" 
                          :status="getCpuStatus(node.currentMemoryUsage, node.maxMemory)"
                          :stroke-width="10"
                      />
                      <div class="detail">{{ Math.round(node.currentMemoryUsage) }} / {{ Math.round(node.maxMemory) }} MB</div>
                  </div>
                  
                  <div class="stat-item">
                      <span class="label">BATTERY</span>
                      <el-progress 
                          :percentage="Math.round(node.battery)" 
                          :color="node.battery > 50 ? '#13ce66' : (node.battery > 20 ? '#e6a23c' : '#ff4949')"
                          :stroke-width="8"
                      />
                  </div>
                  
                  <div class="stat-item inline-stats mt-2">
                      <div><span class="label">TASKS</span> <span class="value">{{ node.activeTasksCount }}</span></div>
                      <div><span class="label">POS</span> <span class="value-small">[{{ Math.round(node.x) }}, {{ Math.round(node.y) }}]</span></div>
                      <div><span class="label">BW</span> <span class="value-small">{{ node.networkBandwidth }} Mbps</span></div>
                  </div>
              </div>
            </div>
         </el-col>
      </el-row>
    </el-card>

    <el-card class="cyber-card mt-3">
        <template #header><div class="cyber-header"><span class="cyber-title">云端兜底集群 (Tier-3)</span></div></template>
        <div style="display: flex; gap: 20px">
           <div class="cyber-uav-card cloud-card" style="width: 300px">
              <div class="node-header">
                 <h3 style="color: #3b82f6;">☁️ 云端总服务器 SVR-1</h3>
                 <el-tag type="primary" effect="dark" size="small">STABLE</el-tag>
              </div>
              <div class="node-stats">
                 <div class="stat-item">
                    <span class="label">资源配额</span>
                    <div class="detail" style="color: #c9d1d9;">无限缩放 (Infinite)</div>
                 </div>
                 <div class="stat-item">
                    <span class="label">延迟基数</span>
                    <div class="detail" style="color: #c9d1d9;">WAN RTT ~ 1.5s</div>
                 </div>
              </div>
           </div>
        </div>
    </el-card>

    <!-- 节点热升级 / 容量扩缩容弹窗 -->
    <el-dialog v-model="upgradeDialogVisible" title="节点动态扩缩容 (Hot-Swap Upgrade)" width="400px" custom-class="cyber-dialog">
      <el-form label-width="100px" class="cyber-form" label-position="left">
        <el-form-item label="核心信标" class="cyber-form-item">
          <div style="color: #00ffcc; font-family: monospace">{{ selectedUpgradeNode?.id }}</div>
        </el-form-item>
        <el-form-item label="上限 CPU" class="cyber-form-item">
          <el-slider v-model="upgradeForm.maxCpu" :min="1" :max="16" :step="1" show-stops />
        </el-form-item>
        <el-form-item label="内存(MB)" class="cyber-form-item">
          <el-slider v-model="upgradeForm.maxMemory" :min="512" :max="16384" :step="512" />
        </el-form-item>
        <el-form-item label="高频带宽" class="cyber-form-item">
          <el-slider v-model="upgradeForm.networkBandwidth" :min="10" :max="1000" :step="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="upgradeDialogVisible = false" class="cyber-btn" type="info" plain>取消</el-button>
          <el-button type="warning" @click="submitNodeUpgrade" :loading="upgrading" class="cyber-btn">确认超频固化</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAppStore } from '../store/appStore'
import { api } from '../services/api'
import { ElMessage } from 'element-plus'

const appStore = useAppStore()
const addingDrone = ref(false)
const upgrading = ref(false)
const upgradeDialogVisible = ref(false)
const selectedUpgradeNode = ref<any>(null)
const upgradeForm = ref({
    maxCpu: 4,
    maxMemory: 2048,
    networkBandwidth: 100
})

const activeNodesCount = computed(() => appStore.nodes.filter(n => n.online).length)
const offlineNodesCount = computed(() => appStore.nodes.filter(n => !n.online).length)

const healthStatus = computed(() => {
    const total = appStore.nodes.length
    if (total === 0) return { type: 'normal', text: '无连接' }
    const active = activeNodesCount.value
    if (active === 0) return { type: 'danger', text: '集群完全故障 / OFFLINE' }
    if (active < total) return { type: 'warning', text: '部分节点下线 / DEGRADED' }
    return { type: 'normal', text: '网络健康 / HEALTHY' }
})

const getCpuStatus = (current: number, max: number) => {
  const percent = current / max
  if (percent > 0.8) return 'exception'
  if (percent > 0.6) return 'warning'
  return 'success'
}

const addDrone = async () => {
    addingDrone.value = true
    try {
        await api.addNode()
        ElMessage.success('新的边缘节点已连入集群')
    } catch {
        ElMessage.error('部署失败')
    } finally {
        addingDrone.value = false
    }
}

const deleteDrone = async (id: string) => {
    try {
        await api.deleteNode(id)
        ElMessage.success(`无人机 ${id} 已退网`)
    } catch {
        ElMessage.error('删除信标失败')
    }
}

const emergencyCharge = async (id: string) => {
    try {
        await api.emergencyCharge(id)
        ElMessage.success('⚡ 高能定向充电完毕，信标满血复活！')
    } catch {
        ElMessage.error('紧急充电调度失败')
    }
}

const simulateFault = async () => {
   const activeNodes = appStore.nodes.filter(n => n.online)
   if (activeNodes.length === 0) return
   
   // 随机挑选一台仍在线健康的无人机终端进行打击
   const target = activeNodes[Math.floor(Math.random() * activeNodes.length)]
   if (!target) return
   try {
       await api.setNodeStatus(target.id, false)
       ElMessage.success(`已强制脱机: ${target.name}。相关任务已被重定向进容错队列。`)
   } catch {
       ElMessage.error('注入故障指令失败')
   }
}

const restoreNode = async () => {
   const offlineNodes = appStore.nodes.filter(n => !n.online)
   if (offlineNodes.length === 0) return
   const target = offlineNodes[Math.floor(Math.random() * offlineNodes.length)]
   if (!target) return
   try {
       await api.setNodeStatus(target.id, true)
       ElMessage.success(`已恢复节点: ${target.name}`)
   } catch {
       ElMessage.error('恢复节点失败')
   }
}

const openUpgradeDialog = (node: any) => {
    selectedUpgradeNode.value = node
    upgradeForm.value.maxCpu = node.maxCpu
    upgradeForm.value.maxMemory = node.maxMemory
    upgradeForm.value.networkBandwidth = node.networkBandwidth
    upgradeDialogVisible.value = true
}

const submitNodeUpgrade = async () => {
    if (!selectedUpgradeNode.value) return;
    upgrading.value = true
    try {
        await api.updateNodeConfig(selectedUpgradeNode.value.id, upgradeForm.value)
        ElMessage.success('节点容量热重载穿透成功！')
        upgradeDialogVisible.value = false
    } catch {
        ElMessage.error('无法执行底层扩容熔断命令')
    } finally {
        upgrading.value = false
    }
}
</script>

<style scoped>
.cluster-container {
   padding-bottom: 30px;
}
.mt-3 {
    margin-top: 15px;
}
.mt-2 {
    margin-top: 10px;
}
.cloud-card {
    border: 1px solid #3b82f6 !important;
    background: #0d1117 !important;
}

:deep(.cyber-dialog) {
  background-color: #0d1117 !important;
  border: 1px solid #30363d;
  border-radius: 8px;
}
:deep(.cyber-dialog .el-dialog__header) {
  border-bottom: 1px solid #30363d;
  margin-right: 0;
  padding-bottom: 15px;
}
:deep(.cyber-dialog .el-dialog__title) {
  color: #c9d1d9;
  font-weight: bold;
}
</style>
