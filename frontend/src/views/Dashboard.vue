<template>
  <div class="dashboard-container">
    <div class="main-content flex-row">
      <!-- 左侧面板: 控制台 -->
      <div class="left-panel">
        <el-card class="cyber-card control-card" shadow="always">
          <template #header>
            <div class="card-header cyber-header">
              <span class="cyber-title">计算任务下发 / COMMAND</span>
            </div>
          </template>

          <el-form :model="taskForm" label-width="80px" label-position="left" class="cyber-form">
            <el-tabs v-model="dispatchMode" class="cyber-tabs">
              <el-tab-pane label="单次/批次 (Batch)" name="batch"></el-tab-pane>
              <el-tab-pane label="持续演进 (Traffic)" name="traffic"></el-tab-pane>
            </el-tabs>

            <el-form-item label="任务类型" class="cyber-form-item" v-if="dispatchMode === 'batch'">
              <el-select v-model="taskForm.type" placeholder="选择类型" style="width: 100%">
                <el-option label="图像处理 (平衡)" value="IMAGE_PROCESSING" />
                <el-option label="视频流分析 (高负载)" value="VIDEO_ANALYSIS" />
                <el-option label="传感数据聚合 (低延迟)" value="SENSOR_DATA" />
              </el-select>
            </el-form-item>

            <el-form-item label="调度算法" class="cyber-form-item">
              <div style="display: flex; flex-direction: column; gap: 4px; width: 100%">
                <el-switch v-model="abcMode" active-text="全算法对比" inactive-text="单算法" style="width: fit-content" />
                <el-select v-if="!abcMode" v-model="algorithm" placeholder="选择算法" size="small" style="width: 100%">
                  <el-option label="Greedy (贪婪)" value="greedy" />
                  <el-option label="WFQ (带宽多目标)" value="wfq" />
                  <el-option label="Geo (物理拓扑最短)" value="geo" />
                  <el-option label="Custom (自定义加权)" value="custom" />
                </el-select>
              </div>
            </el-form-item>

            <div v-if="!abcMode && algorithm === 'custom'" class="custom-weights-box">
              <el-form-item label="距离权重" class="cyber-form-item custom-slider-item">
                <el-slider v-model="taskForm.customW1" :min="0" :max="1.0" :step="0.1" show-stops />
              </el-form-item>
              <el-form-item label="CPU权重" class="cyber-form-item custom-slider-item">
                <el-slider v-model="taskForm.customW2" :min="0" :max="1.0" :step="0.1" show-stops />
              </el-form-item>
              <el-form-item label="电量权重" class="cyber-form-item custom-slider-item">
                <el-slider v-model="taskForm.customW3" :min="0" :max="1.0" :step="0.1" show-stops />
              </el-form-item>
            </div>

            <el-form-item label="数据载荷(MB)" class="cyber-form-item">
              <el-input-number v-model="taskForm.dataSize" :min="1" :max="1000" style="width: 100%" />
            </el-form-item>

            <el-form-item label="所需CPU" class="cyber-form-item">
              <el-slider v-model="taskForm.requiredCpu" :min="0.5" :max="8" :step="0.5" show-stops />
            </el-form-item>

            <el-form-item label="所需内存(MB)" class="cyber-form-item">
              <el-input-number v-model="taskForm.requiredMemory" :min="64" :max="8192" :step="64" style="width: 100%" />
            </el-form-item>

            <el-form-item label="任务优先级" class="cyber-form-item">
              <el-select v-model="taskForm.priority" style="width: 100%">
                <el-option label="1级 (低)" :value="1" />
                <el-option label="2级 (普通)" :value="2" />
                <el-option label="3级 (普通)" :value="3" />
                <el-option label="4级 (高)-可插队" :value="4" />
                <el-option label="5级 (紧急)-可插队" :value="5" />
              </el-select>
            </el-form-item>

            <el-form-item label="边缘卸载算法" class="cyber-form-item">
              <el-select v-model="taskForm.offloadAlgorithm" placeholder="选择卸载评判算法" style="width: 100%">
                <el-option label="最低延迟优先 (Latency)" value="latency" />
                <el-option label="最低能耗优先 (Energy)" value="energy" />
              </el-select>
            </el-form-item>

            <div v-if="dispatchMode === 'batch'">
              <el-form-item label="并发峰值" class="cyber-form-item">
                <el-slider v-model="batchCount" :min="1" :max="100" show-input />
              </el-form-item>
              
              <el-button type="primary" @click="submitTasks" :loading="loading" class="cyber-btn w-full mt-2">
                🚀 发布突发计算任务
              </el-button>
            </div>

            <div v-if="dispatchMode === 'traffic'">
              <el-form-item label="泊松到达率(λ)" class="cyber-form-item">
                <el-slider v-model="trafficLambda" :min="1" :max="50" :step="1" show-input />
                <div style="font-size: 10px; color: #8b949e; line-height: 1.2; margin-top: 4px;">每秒平均泊松达到任务数 E(X)=λ. 任务种类与载荷将通过蒙特卡洛随机采样.</div>
              </el-form-item>

              <el-button v-if="!isTrafficActive" type="success" @click="toggleTraffic" class="cyber-btn w-full mt-2">
                📡 启动泊松流量注入
              </el-button>
              <el-button v-else type="danger" @click="toggleTraffic" class="cyber-btn w-full mt-2">
                🛑 停止流量注入
              </el-button>
            </div>
          </el-form>
        </el-card>

        <el-card class="cyber-card control-card mt-3">
          <template #header><div class="cyber-header"><span class="cyber-title">任务追踪导航</span></div></template>
          <div style="font-size: 13px; color: #8b949e; line-height: 1.6; margin-bottom: 20px">
            任务下发后将自动流转至边缘网格。<br/>需要审查节点执行时序？
          </div>
          <el-button type="warning" class="cyber-btn w-full" @click="$router.push('/trace')">
            📍 进入追踪审计台
          </el-button>
        </el-card>
      </div>

      <!-- 中间面板: 仅雷达拓扑图 -->
      <div class="center-panel">
        <el-card class="cyber-card radar-card" shadow="always" :body-style="{ height: '100%', padding: '0', position: 'relative' }">
          <div class="radar-header">
            <span class="cyber-title">动态地形遥感网 / ACTIVE TOPOLOGY</span>
            <div class="radar-legend">
              <span class="legend-item"><span class="color-box uav-color"></span>信关站 (UAV)</span>
              <span class="legend-item"><span class="color-box cloud-color"></span>云核心 (SVR)</span>
              <span class="legend-item" v-if="appStore.user"><span class="color-box user-color"></span>你 (OP)</span>
              <span class="legend-item" v-if="onlineUsers.length > 0"><span class="color-box online-user-legend"></span>其他 ({{ onlineUsers.length }})</span>
            </div>
          </div>
          
          <!-- 右侧边栏：实时集群数据大屏面板 (KPI Panel) -->
          <div class="kpi-panel">
             <div class="kpi-block">
                <div class="kpi-label">ACTIVE NODES</div>
                <div class="kpi-value highlight-success">{{ activeNodesCount }}<span class="kpi-sub">/{{ appStore.nodes.length }}</span></div>
             </div>
             <div class="kpi-block">
                <div class="kpi-label">CLUSTER CPU LOAD</div>
                <div class="kpi-value" :class="avgCpuLoad > 70 ? 'highlight-danger' : 'highlight-accent'">{{ avgCpuLoad.toFixed(1) }}<span class="kpi-sub">%</span></div>
             </div>
             <div class="kpi-block">
                <div class="kpi-label">AVG BATTERY</div>
                <div class="kpi-value" :class="avgBattery < 30 ? 'highlight-danger' : 'highlight-accent'">{{ avgBattery.toFixed(1) }}<span class="kpi-sub">%</span></div>
             </div>
             <div class="kpi-block">
                <div class="kpi-label">RUNNING TASKS</div>
                <div class="kpi-value highlight-warning">{{ appStore.activeTasks.length }}</div>
             </div>
          </div>
          <svg ref="radarSvg" class="radar-svg" width="100%" height="100%" :viewBox="currentViewBox" preserveAspectRatio="none"
               @wheel.prevent="handleZoom" @mousedown="startPan" @mousemove="doPan" @mouseup="endPan" @mouseleave="endPan">
            <defs>
              <!-- Dynamic Gradients for Drone Battery Indicator -->
              <linearGradient v-for="n in appStore.nodes" :key="`grad-${n.id}`" :id="`grad-${n.id}`" 
                 gradientUnits="userSpaceOnUse" x1="-5" y1="0" x2="4" y2="0">
                <stop offset="0%" stop-color="#00ffcc" />
                <stop :offset="`${Math.max(0, n.battery)}%`" stop-color="#00ffcc" />
                <stop :offset="`${Math.max(0, n.battery)}%`" stop-color="#4b5563" />
                <stop offset="100%" stop-color="#4b5563" />
              </linearGradient>

              <radialGradient id="radar-glow" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stop-color="rgba(0, 255, 204, 0.15)"/>
                <stop offset="80%" stop-color="rgba(0, 255, 204, 0.05)"/>
                <stop offset="100%" stop-color="rgba(0, 255, 204, 0)"/>
              </radialGradient>
              <filter id="glow">
                <feGaussianBlur stdDeviation="1.5" result="coloredBlur"/>
                <feMerge>
                  <feMergeNode in="coloredBlur"/>
                  <feMergeNode in="SourceGraphic"/>
                </feMerge>
              </filter>
            </defs>

            <!-- 背景网格坐标线 -->
            <g class="grid-lines">
              <line v-for="i in 10" :key="`h${i}`" x1="0" :y1="i*10" x2="100" :y2="i*10" />
              <line v-for="i in 10" :key="`v${i}`" :x1="i*10" y1="0" :x2="i*10" y2="100" />
            </g>

            <!-- 实时通信链路光束 -->
            <line v-for="line in activeLines" :key="line.id"
                  :x1="line.x1" :y1="line.y1" :x2="line.x2" :y2="line.y2"
                  :class="line.type === 'edge' ? 'edge-link' : (line.type === 'cloud' ? 'cloud-link' : (line.type === 'split' ? 'split-link' : 'fallback-link'))" />

            <!-- 工作负载窃取重定向链路 -->
            <line v-for="steal in activeSteals" :key="steal.id"
                  :x1="steal.x1" :y1="steal.y1" :x2="steal.x2" :y2="steal.y2"
                  class="steal-link" />

            <!-- 边缘计算节点 (UAV) -->
            <g v-for="node in appStore.nodes" :key="`radar-${node.id}`" 
               class="radar-node" 
               :class="{'offline-node': !node.online, 'is-dragging': draggedNode?.id === node.id, 'busy-node': node.activeTasksCount > 0}"
               :transform="`translate(${node.x}, ${node.y})`"
               @mousedown.stop="startNodeDrag(node)"
               style="cursor: grab;">
               
               <!-- 状态小图标展示 -->
               <g class="status-indicator">
                  <text v-if="!node.online && !node.battery" x="-12" y="-5" fill="#ff4949" font-size="4">❌ 断联</text>
                  <text v-else-if="node.rthMode" x="-12" y="-5" fill="#e6a23c" font-size="4">⚠️ RTH</text>
                  <text v-else-if="node.charging" x="-12" y="-5" fill="#13ce66" font-size="4">⚡ 充电</text>
                  <text v-else-if="!node.online" x="-12" y="-5" fill="#ff4949" font-size="4">💤 离线</text>
               </g>

              <g class="drone-icon">
                 <path d="M 4 0 L 1 -1 L -1 -5 L -2 -5 L 0 -1 L -3 -1 L -4 -2.5 L -5 -2.5 L -4 0 L -5 2.5 L -4 2.5 L -3 1 L 0 1 L -2 5 L -1 5 L 1 1 L 4 0 Z" :fill="node.online ? `url(#grad-${node.id})` : '#4b5563'" />
              </g>
              <text x="8" y="2" class="node-label" :fill="node.activeTasksCount > 0 ? '#ff4949' : ''">{{ node.name }}</text>
              <circle v-if="node.online" cx="0" cy="0" :r="node.activeTasksCount > 0 ? 15 : 12" class="node-ping" :class="{'busy-ping': node.activeTasksCount > 0}" />
            </g>
            
            <!-- 云端核心服务器 (SVR) -->
            <g v-for="svr in servers" :key="`radar-svr-${svr.id}`" 
               class="radar-svr" 
               :transform="`translate(${svr.x}, ${svr.y})`">
              <g class="server-icon" filter="url(#glow)">
                 <rect x="-6" y="-8" width="12" height="16" rx="1" fill="none" stroke="#3b82f6" stroke-width="1.2"/>
                 <line x1="-6" y1="-3" x2="6" y2="-3" stroke="#3b82f6" stroke-width="0.8"/>
                 <line x1="-6" y1="2" x2="6" y2="2" stroke="#3b82f6" stroke-width="0.8"/>
                 <circle cx="-3" cy="-5.5" r="1" fill="#3b82f6" />
                 <circle cx="-3" cy="-0.5" r="1" fill="#3b82f6" />
                 <circle cx="-3" cy="4.5" r="1" fill="#3b82f6" />
                 <circle cx="3" cy="-5.5" r="1" fill="#13ce66" class="server-blink" />
                 <circle cx="3" cy="-0.5" r="1" fill="#13ce66" class="server-blink" />
                 <circle cx="3" cy="4.5" r="1" fill="#13ce66" class="server-blink" />
              </g>
              <text x="10" y="2" class="node-label" fill="#3b82f6">{{ svr.name }}</text>
              <circle cx="0" cy="0" r="15" class="svr-ping" />
            </g>

            <!-- 操作员控制台原点 -->
            <g v-if="appStore.user" class="radar-user" :transform="`translate(${appStore.user.x}, ${appStore.user.y})`">
              <g class="user-icon" filter="url(#glow)">
                 <circle cx="0" cy="-2.5" r="1.5" fill="#e6a23c" />
                 <path d="M -3 3 L -3 1 C -3 0 -1 -0.5 0 -0.5 C 1 -0.5 3 0 3 1 L 3 3 Z" fill="#e6a23c" />
              </g>
              <text x="8" y="2" class="node-label user-label">{{ appStore.user.username }}</text>
              <circle cx="0" cy="0" r="18" class="user-ping" />
            </g>

            <!-- 其他在线用户 -->
            <g v-for="onlineUser in onlineUsers" :key="`online-user-${onlineUser.username}`"
               class="radar-online-user"
               :transform="`translate(${onlineUser.x}, ${onlineUser.y})`">
              <g class="online-user-icon" filter="url(#glow)">
                 <circle cx="0" cy="-2.5" r="1.2" fill="#a855f7" />
                 <path d="M -2.5 2.5 L -2.5 0.5 C -2.5 -0.5 -0.5 -1 0 -1 C 0.5 -1 2.5 -0.5 2.5 0.5 L 2.5 2.5 Z" fill="#a855f7" />
              </g>
              <text x="6" y="2" class="node-label online-user-label">{{ onlineUser.username }}</text>
              <circle cx="0" cy="0" r="14" class="online-user-ping" />
            </g>

            <!-- 返航充电基站 (Home Base 0,0) -->
            <g class="radar-rth" :transform="`translate(0, 0)`">
              <circle cx="0" cy="0" r="8" fill="rgba(19, 206, 102, 0.15)" stroke="#13ce66" stroke-width="0.8" stroke-dasharray="1" />
              <circle cx="0" cy="0" r="12" fill="none" stroke="#13ce66" stroke-width="0.3" stroke-dasharray="3" class="spin-slow" />
              <text x="-12" y="-10" class="node-label" fill="#13ce66" style="font-size: 3.5px; opacity: 0.8">⚡ 返航充能基站 (0,0)</text>
            </g>

            <!-- 任务发射源头动画波纹 -->
            <g v-for="origin in activeOrigins" :key="`origin-${origin.id}`"
               class="radar-task-origin"
               :transform="`translate(${origin.x}, ${origin.y})`">
               <circle cx="0" cy="0" r="1.5" class="origin-dot" filter="url(#glow)"/>
               <circle cx="0" cy="0" r="6" class="origin-ping" />
            </g>
          </svg>
          
          <div class="radar-scan"></div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '../store/appStore'
import { useAuthStore } from '../store/authStore'
import type { TaskInfo } from '../store/appStore'
import { api } from '../services/api'
import { ElMessage } from 'element-plus'

const appStore = useAppStore()
const authStore = useAuthStore()

const loading = ref(false)
const batchCount = ref(1)
const dispatchMode = ref('batch')
const trafficLambda = ref(5)
const isTrafficActive = ref(false)

const algorithm = ref('geo')
const abcMode = ref(true)

const radarSvg = ref<SVGElement | null>(null)
const viewBoxData = ref({ x: -20, y: -20, w: 140, h: 140 })
const isPanning = ref(false)
const panStart = ref({ x: 0, y: 0 })
const draggedNode = ref<any>(null)

// KPI Computeds
const activeNodesCount = computed(() => appStore.nodes.filter(n => n.online).length);

const avgCpuLoad = computed(() => {
    if (appStore.nodes.length === 0) return 0;
    let totalCpu = 0;
    let totalUsed = 0;
    appStore.nodes.forEach(n => {
        totalCpu += n.maxCpu;
        totalUsed += n.currentCpuUsage;
    });
    return totalCpu > 0 ? (totalUsed / totalCpu) * 100 : 0;
});

const avgBattery = computed(() => {
    if (appStore.nodes.length === 0) return 0;
    const sum = appStore.nodes.reduce((acc, n) => acc + n.battery, 0);
    return sum / appStore.nodes.length;
});

const startNodeDrag = (node: any) => {
    draggedNode.value = node;
}

const currentViewBox = computed(() => {
  return `${viewBoxData.value.x} ${viewBoxData.value.y} ${viewBoxData.value.w} ${viewBoxData.value.h}`
})

const handleZoom = (e: WheelEvent) => {
    const scaleFactor = e.deltaY > 0 ? 1.1 : 0.9
    const svgRect = radarSvg.value?.getBoundingClientRect()
    if (!svgRect) return

    const mouseX = e.clientX - svgRect.left
    const mouseY = e.clientY - svgRect.top
    
    const relX = mouseX / svgRect.width
    const relY = mouseY / svgRect.height

    const mapX = viewBoxData.value.x + relX * viewBoxData.value.w
    const mapY = viewBoxData.value.y + relY * viewBoxData.value.h

    viewBoxData.value.w = Math.max(10, Math.min(200, viewBoxData.value.w * scaleFactor))
    viewBoxData.value.h = Math.max(10, Math.min(200, viewBoxData.value.h * scaleFactor))

    viewBoxData.value.x = mapX - relX * viewBoxData.value.w
    viewBoxData.value.y = mapY - relY * viewBoxData.value.h
}

const startPan = (e: MouseEvent) => {
    isPanning.value = true
    panStart.value = { x: e.clientX, y: e.clientY }
}

const doPan = (e: MouseEvent) => {
    const svgRect = radarSvg.value?.getBoundingClientRect()
    if (!svgRect) return

    if (draggedNode.value) {
        const mouseX = e.clientX - svgRect.left
        const mouseY = e.clientY - svgRect.top
        
        const relX = mouseX / svgRect.width
        const relY = mouseY / svgRect.height

        draggedNode.value.x = viewBoxData.value.x + relX * viewBoxData.value.w
        draggedNode.value.y = viewBoxData.value.y + relY * viewBoxData.value.h
        return
    }

    if (!isPanning.value) return
    const dx = e.clientX - panStart.value.x
    const dy = e.clientY - panStart.value.y

    // 将基于像素的 dx/dy 偏移转换为 viewBox 坐标系尺度
    const unitsPerPixelX = viewBoxData.value.w / svgRect.width
    const unitsPerPixelY = viewBoxData.value.h / svgRect.height

    viewBoxData.value.x -= dx * unitsPerPixelX
    viewBoxData.value.y -= dy * unitsPerPixelY

    panStart.value = { x: e.clientX, y: e.clientY }
}

const endPan = async () => {
    isPanning.value = false
    if (draggedNode.value) {
        try {
            await api.setNodePosition(draggedNode.value.id, draggedNode.value.x, draggedNode.value.y)
            ElMessage.success({ message: `节点 ${draggedNode.value.name} 已就位悬停`, duration: 2000 })
        } catch (e) {
            ElMessage.error('节点拖拽位置同步失败')
        }
        draggedNode.value = null
    }
}

const taskForm = ref<TaskInfo>({
  type: 'IMAGE_PROCESSING',
  dataSize: 50,
  requiredCpu: 2,
  requiredMemory: 512,
  priority: 3,
  offloadAlgorithm: 'latency',
  customW1: 0.5,
  customW2: 0.5,
  customW3: 0.5
})

const activeOrigins = ref<{id: string, x: number, y: number}[]>([])
const activeLines = computed(() => {
    return appStore.activeTasks.flatMap(task => {
        let lines = [];
        const isSplit = task.assignedUavId && task.assignedUavId.includes('& CLOUD');
        let edgeUavId = task.assignedUavId;
        
        if (isSplit) {
            edgeUavId = task.assignedUavId!.split(' & ')[0];
        }

        if (edgeUavId && !edgeUavId.startsWith('CLOUD')) {
            const assignedNode = appStore.nodes.find(n => n.id === edgeUavId);
            if (assignedNode) {
                lines.push({
                    id: `line-edge-${task.id}`,
                    type: 'edge',
                    x1: task.originX || 50,
                    y1: task.originY || 50,
                    x2: assignedNode.x,
                    y2: assignedNode.y
                });
            }
        }

        if (task.assignedUavId && task.assignedUavId.startsWith('CLOUD')) {
            // 完全卸载到云端 - 蓝色链路
            lines.push({
                id: `line-cloud-${task.id}`,
                type: 'cloud',
                x1: task.originX || 50,
                y1: task.originY || 50,
                x2: 90,
                y2: 10
            });
        } else if (isSplit) {
            // 部分卸载 - 紫色链路
            lines.push({
                id: `line-split-${task.id}`,
                type: 'split',
                x1: task.originX || 50,
                y1: task.originY || 50,
                x2: 90,
                y2: 10
            });
        }

        if (lines.length === 0) {
            lines.push({
                id: `line-fallback-${task.id}`,
                type: 'fallback',
                x1: task.originX || 50,
                y1: task.originY || 50,
                x2: 90,
                y2: 10
            });
        }
        
        return lines;
    });
});

const activeSteals = computed(() => {
    return appStore.stealEvents.map(steal => {
        const fromNode = appStore.nodes.find(n => n.id === steal.fromNodeId)
        const toNode = appStore.nodes.find(n => n.id === steal.toNodeId)
        if (!fromNode || !toNode) return null
        return {
            id: steal.id,
            x1: fromNode.x,
            y1: fromNode.y,
            x2: toNode.x,
            y2: toNode.y
        }
    }).filter(s => s !== null) as {id: string, x1: number, y1: number, x2: number, y2: number}[]
})

const servers = ref([
    { id: 'CLOUD-1', name: '☁️ 云端服务器', x: 90, y: 10 }
])

// 在线其他用户（排除自己）
const onlineUsers = computed(() => {
    if (!authStore.user) return []
    return authStore.onlineUsers.filter(u => u.username !== authStore.user?.username)
})

// 雷达图波纹特效与数据处理 (基于真实业务数据的精确映射)
const triggerOriginPulse = (tasks: any[]) => {
    // 在雷达范围内生成分散的数据点以展现发射散布效果
    tasks.forEach((t, i) => {
        const id = `origin-${Date.now()}-${i}`
        activeOrigins.value.push({ id, x: t.originX, y: t.originY })
        
        // 1.5秒后消散发射源追踪波纹
        setTimeout(() => {
            activeOrigins.value = activeOrigins.value.filter(o => o.id !== id)
        }, 1500)
    })
}

// ... 轮询逻辑已迁移至 Analytics 后端，但由于 Dashboard 是触发源
// Dashboard 仍需保留轮询机制并将结果推送入 store！以便 Analytics 能够直接读取。
let pollingIntervals: Record<string, number> = {};
let batchIdCounter = appStore.performanceStats.history.length;

const pollBatchMetrics = (batchId: string, usedAlgorithm: string, meta: any) => {
    ElMessage.info({ message: '正在聚合单算法真实遥测数据...', duration: 2000 })
    pollingIntervals[batchId] = window.setInterval(async () => {
        try {
            const resp = await api.getBatchMetrics(batchId);
            if (resp.data && resp.data.status === 'FINISHED') {
                window.clearInterval(pollingIntervals[batchId]);
                delete pollingIntervals[batchId];
                
                batchIdCounter++
                const dataPoint: any = {
                    batch: `#${batchIdCounter} ${meta.taskCount}×${meta.cpu}C`,
                    meta: meta,
                    latency: { greedy: null, wfq: null, geo: null },
                    energy: { greedy: null, wfq: null, geo: null },
                    bandwidth: { greedy: null, wfq: null, geo: null },
                    success: { greedy: null, wfq: null, geo: null }
                }
                dataPoint.latency[usedAlgorithm] = resp.data.latency;
                dataPoint.energy[usedAlgorithm] = resp.data.energy;
                dataPoint.bandwidth[usedAlgorithm] = resp.data.bandwidth;
                dataPoint.success[usedAlgorithm] = resp.data.success;

                appStore.performanceStats.history.push(dataPoint)
                ElMessage.success({ message: `遥测完成，已入库！请前往[多维效能]面板查看`, duration: 4000 })
            }
        } catch (e) {
            console.error("Polling error for", batchId, e);
        }
    }, 2000);
}

const pollABCMetricsSequential = async (batchId: string, alg: string): Promise<any> => {
    return new Promise((resolve) => {
        let pollCount = 0;
        const interval = window.setInterval(async () => {
            pollCount++;
            if (pollCount > 60) { // Timeout after ~120s
                window.clearInterval(interval);
                resolve(null);
                return;
            }
            try {
                const resp = await api.getBatchMetrics(batchId);
                if (resp.data && resp.data.status === 'FINISHED') {
                    window.clearInterval(interval);
                    resolve(resp.data);
                }
            } catch (e) {
                console.error(`Error polling ABC metrics for ${alg}`, e);
            }
        }, 2000);
    });
}

const submitTasks = async () => {
  if (batchCount.value < 1) return
  const hasOnlineCores = appStore.nodes.some(n => n.online)
  if (!hasOnlineCores) {
    ElMessage.warning('链路失效：无存活节点')
    return;
  }
  loading.value = true
  const userX = appStore.user?.x || 50;
  const userY = appStore.user?.y || 50;

  const buildTasks = (alg: string, batchId: string): any[] => {
    const tasks: any[] = []
    const operatorName = appStore.user?.username || 'Ops'
    for (let i = 0; i < batchCount.value; i++) {
      const t = { ...taskForm.value }
      t.originX = userX
      t.originY = userY
      t.batchId = batchId
      t.operatorName = operatorName
      t.schedulingAlgorithm = alg
      if (alg === 'custom') {
          t.customW1 = taskForm.value.customW1
          t.customW2 = taskForm.value.customW2
          t.customW3 = taskForm.value.customW3
      }
      tasks.push(t)
    }
    return tasks
  }

  try {
    const meta = {
        taskCount: batchCount.value,
        cpu: taskForm.value.requiredCpu,
        memory: taskForm.value.requiredMemory || 512,
        dataSize: taskForm.value.dataSize,
        type: taskForm.value.type
    }

    if (abcMode.value) {
      ElMessage.info({ message: '启动全算法严格串行基准测试 (Sandbox Isolation模式)...', duration: 3000 });
      await api.createSnapshot(); // 1. 创建干净物理沙盒快照

      const timestamp = Date.now();
      const algorithms = ['greedy', 'wfq', 'geo'];
      const results: Record<string, any> = {};

      for (const alg of algorithms) {
        ElMessage.warning({ message: `[${alg.toUpperCase()}] 沙盒隔离容器启动...`, duration: 2000 });
        const batchId = `BATCH-${timestamp}-${alg}`;
        const tasks = buildTasks(alg, batchId);
        triggerOriginPulse(tasks);
        
        await api.submitBatchTasks(tasks);
        
        const metrics = await pollABCMetricsSequential(batchId, alg);
        if (metrics) {
            results[alg] = metrics;
        } else {
            ElMessage.error(`[${alg.toUpperCase()}] 测试超时失效`);
            loading.value = false;
            return;
        }
        
        // 测试完成后，如果是最后一环不着急恢复，可以留给下一次开始时，但为了每次测试后都干干净净，立刻回滚
        ElMessage.success({ message: `[${alg.toUpperCase()}] 演算完毕，执行物理时间沙盒回滚...`, duration: 2000 });
        await api.rollbackSnapshot();
      }

      batchIdCounter++;
      const dataPoint = {
          batch: `#${batchIdCounter} ${meta.taskCount}×${meta.cpu}C`,
          meta: meta,
          latency: { greedy: results.greedy?.latency, wfq: results.wfq?.latency, geo: results.geo?.latency },
          energy: { greedy: results.greedy?.energy, wfq: results.wfq?.energy, geo: results.geo?.energy },
          bandwidth: { greedy: results.greedy?.bandwidth, wfq: results.wfq?.bandwidth, geo: results.geo?.bandwidth },
          success: { greedy: results.greedy?.success, wfq: results.wfq?.success, geo: results.geo?.success }
      };
      appStore.performanceStats.history.push(dataPoint);
      ElMessage.success({ message: '全算法隔离遥测数据对比完成！已生成报告', duration: 4000 });
      loading.value = false;
    } else {
      const batchId = 'BATCH-' + Date.now()
      const tasks = buildTasks(algorithm.value, batchId)
      triggerOriginPulse(tasks)
      await api.submitBatchTasks(tasks)
      loading.value = false
      pollBatchMetrics(batchId, algorithm.value, meta)
    }
  } catch (error) {
    ElMessage.error('发射失败')
    loading.value = false
  }
}

const toggleTraffic = async () => {
  try {
    if (!isTrafficActive.value) {
      await api.startTraffic({
        lambda: trafficLambda.value,
        algorithm: abcMode.value ? null : algorithm.value,
        originX: appStore.user?.x ?? 50,
        originY: appStore.user?.y ?? 50,
        customW1: taskForm.value.customW1,
        customW2: taskForm.value.customW2,
        customW3: taskForm.value.customW3,
      })
      isTrafficActive.value = true
      ElMessage.success({ message: `📡 泊松流量生成器已启动 (λ=${trafficLambda.value} tasks/s)`, duration: 3000 })
    } else {
      await api.stopTraffic()
      isTrafficActive.value = false
      ElMessage.warning({ message: '🛑 流量注入已停止', duration: 2000 })
    }
  } catch (e) {
    ElMessage.error('流量生成器控制失败')
  }
}

// 组件卸载时清理所有定时器，防止内存泄漏
onUnmounted(() => {
    Object.values(pollingIntervals).forEach(intervalId => {
        window.clearInterval(intervalId);
    });
    pollingIntervals = {};
});

// Sync traffic generator status with backend on page load
onMounted(async () => {
  try {
    const resp = await api.getTrafficStatus()
    isTrafficActive.value = resp.data.active
    if (resp.data.lambda) trafficLambda.value = resp.data.lambda
  } catch (e) {
    ElMessage.warning({ message: '获取流量状态失败，请检查网络连接', duration: 3000 })
  }

  // Fetch initial online users
  try {
    const resp = await api.getOnlineUsers()
    if (resp.data && Array.isArray(resp.data)) {
      authStore.setOnlineUsers(resp.data)
    }
  } catch (e) {
    console.error('Failed to fetch online users', e)
  }
})
</script>

<style scoped>
.flex-row {
  display: flex;
  height: 100%;
  gap: 15px;
}

.left-panel {
  width: 320px;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow-y: auto;
  padding-right: 5px;
}
.left-panel::-webkit-scrollbar { width: 4px; }
.left-panel::-webkit-scrollbar-thumb { background: #30363d; border-radius: 2px; }

.center-panel {
  flex: 1;
  min-width: 0;
  height: 100%;
}

.radar-card {
  height: 100%;
  border-color: #00ffcc33;
  overflow: hidden;
}

.radar-header {
  position: absolute;
  top: 15px;
  left: 20px;
  z-index: 10;
  pointer-events: none;
}

.radar-legend {
  display: flex;
  gap: 15px;
  margin-top: 5px;
  pointer-events: auto;
}

/* KPI Panel */
.kpi-panel {
  position: absolute;
  top: 15px;
  right: 20px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  gap: 12px;
  pointer-events: none;
}
.kpi-block {
  background: rgba(13, 17, 23, 0.6);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(48, 54, 61, 0.6);
  border-radius: 6px;
  padding: 8px 12px;
  min-width: 140px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
}
.kpi-label {
  font-size: 10px;
  color: #8b949e;
  font-family: var(--font-mono);
  letter-spacing: 0.5px;
  margin-bottom: 2px;
}
.kpi-value {
  font-size: 20px;
  font-weight: 700;
  font-family: var(--font-mono);
  line-height: 1;
}
.kpi-sub {
  font-size: 12px;
  color: #8b949e;
  margin-left: 2px;
  font-weight: 400;
}
.highlight-accent { color: #00ffcc; text-shadow: 0 0 8px rgba(0, 255, 204, 0.3); }
.highlight-success { color: #13ce66; text-shadow: 0 0 8px rgba(19, 206, 102, 0.3); }
.highlight-warning { color: #e6a23c; text-shadow: 0 0 8px rgba(230, 162, 60, 0.3); }
.highlight-danger { color: #ff4949; text-shadow: 0 0 8px rgba(255, 73, 73, 0.3); }

.legend-item {
  font-size: 13px;
  color: #c9d1d9;
  display: flex;
  align-items: center;
  gap: 4px;
}

.color-box {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.uav-color { background: #00ffcc; box-shadow: 0 0 5px #00ffcc; }
.cloud-color { background: #3b82f6; box-shadow: 0 0 5px #3b82f6; }
.user-color { background: #e6a23c; box-shadow: 0 0 5px #e6a23c; }
.online-user-legend { background: #a855f7; box-shadow: 0 0 5px #a855f7; }

.custom-weights-box {
  background: rgba(0, 255, 204, 0.03);
  border-left: 2px solid #00ffcc;
  padding: 10px 15px 5px 15px;
  margin-bottom: 18px;
  border-radius: 0 4px 4px 0;
}
.custom-slider-item {
  margin-bottom: 5px !important;
}
.custom-slider-item :deep(.el-form-item__label) {
  font-size: 11px;
  color: #00ffcc;
}

/* ================= 雷达图 SVG 深度定制层 ================= */
.radar-svg {
  background: radial-gradient(circle at center, rgba(13, 17, 23, 0.8) 0%, rgba(6, 8, 10, 1) 100%);
  border-radius: 4px;
  cursor: grab;
}
.radar-svg:active {
  cursor: grabbing;
}

.grid-lines line {
  stroke: rgba(48, 54, 61, 0.4);
  stroke-width: 0.2;
}

.radar-node {
  transition: transform 2s linear;
}
.radar-node.is-dragging {
  transition: none !important; /* 当正处于拖拽操作时，锁定游标位置（切断缓动插值动画） */
}
.radar-node:active {
  cursor: grabbing !important;
}

.node-circle {
  fill: #00ffcc;
}

.offline-node .node-circle {
  fill: #ff4949;
}

.node-label {
  font-size: 3px;
  fill: #c9d1d9;
  font-family: monospace;
}

.node-ping {
  fill: transparent;
  stroke: rgba(139, 148, 158, 0.6);
  stroke-width: 0.5;
  animation: radarPing 2s infinite ease-out;
}

.svr-rect {
  fill: #3b82f6;
}
.svr-ping {
  fill: transparent;
  stroke: #3b82f6;
  stroke-width: 0.5;
  animation: radarPing 3s infinite ease-out 1s;
}

.user-triangle {
  fill: #e6a23c;
}
.user-label {
  fill: #e6a23c;
  font-weight: bold;
  font-size: 4px;
}
.user-ping {
  fill: transparent;
  stroke: #e6a23c;
  stroke-width: 0.8;
  animation: radarPing 2.5s infinite ease-out;
}

.online-user-ping {
  fill: transparent;
  stroke: #a855f7;
  stroke-width: 0.6;
  animation: radarPing 3s infinite ease-out;
}

.online-user-label {
  font-size: 3.5px;
  fill: #a855f7;
}

.origin-dot { fill: #e6a23c; }

.active-link {
  stroke: #e6a23c;
  stroke-width: 0.8;
  stroke-dasharray: 2;
  animation: dash 1s linear infinite;
}

.task-link {
  stroke: #00ffcc;
  stroke-width: 0.6;
  stroke-dasharray: 2, 8;
  stroke-linecap: round;
  animation: flowLine 0.6s linear infinite;
  filter: drop-shadow(0 0 2px rgba(0, 255, 204, 0.5));
}

/* 边缘链路 - 青色 */
.edge-link {
  stroke: #00ffcc;
  stroke-width: 0.6;
  stroke-dasharray: 2, 8;
  stroke-linecap: round;
  animation: flowLine 0.6s linear infinite;
  filter: drop-shadow(0 0 2px rgba(0, 255, 204, 0.5));
}

/* 云端完全卸载链路 - 蓝色 */
.cloud-link {
  stroke: #3b82f6;
  stroke-width: 0.8;
  stroke-dasharray: 4, 6;
  stroke-linecap: round;
  animation: cloudFlow 0.8s linear infinite;
  filter: drop-shadow(0 0 3px rgba(59, 130, 246, 0.6));
}

/* 部分卸载链路 - 紫色 */
.split-link {
  stroke: #a855f7;
  stroke-width: 0.7;
  stroke-dasharray: 3, 7;
  stroke-linecap: round;
  animation: splitFlow 0.7s linear infinite;
  filter: drop-shadow(0 0 3px rgba(168, 85, 247, 0.5));
}

/* 后备/降级链路 - 灰色 */
.fallback-link {
  stroke: #6b7280;
  stroke-width: 0.5;
  stroke-dasharray: 2, 10;
  stroke-linecap: round;
  animation: dash 1s linear infinite;
  filter: none;
}

@keyframes cloudFlow {
  to { stroke-dashoffset: -10; }
}

@keyframes splitFlow {
  to { stroke-dashoffset: -10; }
}

.steal-link {
  stroke: #ffbb00;
  stroke-width: 1.5;
  stroke-dasharray: 4, 12;
  stroke-linecap: round;
  animation: steal-dash 0.8s linear infinite;
}

.busy-node {
  animation: none;
}
.busy-ping {
  stroke: #ff4949 !important;
  stroke-width: 1.0 !important;
  animation: busyRadarPing 1.2s infinite ease-out !important;
}

@keyframes busyRadarPing {
  0% { r: 3; opacity: 1; }
  100% { r: 20; opacity: 0; }
}

@keyframes steal-dash {
  to {
    stroke-dashoffset: -16;
  }
}

.spin-slow {
  transform-origin: 0 0;
  animation: spin 8s linear infinite;
}

@keyframes spin {
  100% {
    transform: rotate(360deg);
  }
}

.origin-ping {
  fill: transparent;
  stroke: #e6a23c;
  stroke-width: 0.5;
  animation: radarPing 1s infinite ease-out;
}

@keyframes radarPing {
  0% { r: 2; opacity: 1; }
  100% { r: 15; opacity: 0; }
}

@keyframes flowLine {
  to { stroke-dashoffset: -6; }
}

.mt-2 { margin-top: 10px; }
.mt-3 { margin-top: 15px; }
</style>
