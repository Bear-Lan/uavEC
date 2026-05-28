<template>
  <div class="trace-container">
    <el-card class="cyber-card control-card" shadow="always">
       <template #header>
          <div class="card-header cyber-header">
             <span class="cyber-title">任务追踪与路由审计 / AUDIT LOG</span>
          </div>
      </template>

      <!-- 主查询列表 Master Table -->
      <div v-if="appStore.completedTasks.length === 0" style="padding: 40px; text-align: center; color: #8b949e">
          <el-empty description="网格中暂无已完成的负载数据" />
      </div>
      
      <el-table v-else :data="[...appStore.completedTasks].reverse()" class="cyber-table" style="width: 100%" height="calc(100vh - 250px)">
        <el-table-column prop="taskName" label="任务代号" width="220">
            <template #default="scope">
              <div style="font-weight: bold; color: #00ffcc;">{{ scope.row.taskName }}</div>
              <div style="font-family: monospace; font-size: 11px; color: #8b949e">{{ scope.row.id.substring(0, 18) }}...</div>
            </template>
        </el-table-column>
        <el-table-column prop="type" label="负载类型" width="180" />
        <el-table-column prop="priority" label="优先级" width="90">
            <template #default="scope">
              <span class="glass-tag" :class="scope.row.priority >= 4 ? 'tag-danger' : 'tag-info'">
                  P{{ scope.row.priority }}
              </span>
            </template>
        </el-table-column>
        <el-table-column label="数据量" width="100">
            <template #default="scope">
                <span style="color:#8b949e;font-size:12px;">{{ scope.row.dataSize }} MB</span>
            </template>
        </el-table-column>
        <el-table-column prop="schedulingAlgorithm" label="调度算法" width="100">
            <template #default="scope">
                <span class="glass-tag tag-info" style="text-transform: uppercase;">
                    {{ scope.row.schedulingAlgorithm || 'N/A' }}
                </span>
            </template>
        </el-table-column>
        <el-table-column prop="assignedUavId" label="边缘承接节点" width="150">
            <template #default="scope">
              <span class="glass-tag" :class="scope.row.assignedUavId?.startsWith('CLOUD') ? 'tag-warning' : 'tag-success'">
                  {{ scope.row.assignedUavId || 'UNKNOWN' }}
              </span>
            </template>
        </el-table-column>
        <el-table-column prop="status" label="终态" width="180">
            <template #default="scope">
              <div style="display: flex; align-items: center; gap: 8px;">
                <span class="glass-tag" :class="scope.row.status === 'FAILED' ? 'tag-danger' : (scope.row.status === 'RUNNING_SPLIT' ? 'tag-warning' : 'tag-success')">
                  {{ scope.row.status }}
                </span>
                <!-- Micro progress bar for running jobs -->
                <div v-if="scope.row.status.startsWith('RUNNING')" class="micro-progress">
                   <div class="micro-progress-bar"></div>
                </div>
              </div>
            </template>
        </el-table-column>
        <el-table-column label="深度查全" fixed="right" width="120">
            <template #default="scope">
                <el-button size="small" type="primary" plain @click="openDrawer(scope.row.id)">
                    <el-icon><Search /></el-icon> 瀑布流
                </el-button>
            </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 边栏抽屉：时序详情分析 Detail Drawer for Timeline -->
    <el-drawer
      v-model="drawerVisible"
      :title="'追溯报告: ' + (traceData?.taskName || searchTaskId?.substring(0,8))"
      direction="rtl"
      size="500px"
      custom-class="cyber-drawer"
    >
       <div v-if="loading" style="padding: 40px; text-align: center;">
         <el-icon class="is-loading" :size="30"><Loading /></el-icon>
         <div style="margin-top: 15px; color: #8b949e">正在向节点提取时空日志...</div>
       </div>
       <div v-else-if="!traceData" style="padding: 40px; text-align: center; color: #8b949e">
          <el-empty description="网格中未检索到该任务日志或已过期清理" />
       </div>
       <div v-else class="trace-timeline-wrapper">
          <el-timeline style="margin-top: 10px;">
            <el-timeline-item center type="info" :timestamp="formatDate(traceData.createdTime)">
              <span class="phase-title">MISSION CREATED / 负载生成</span>
              <div class="phase-detail">Origin: {{ appStore.user?.username || '控制中心' }}</div>
            </el-timeline-item>
            
            <el-timeline-item center :type="traceData.queueLatency > 2000 ? 'danger' : 'warning'" :timestamp="formatDate(traceData.queueStartTime)">
              <span class="phase-title">QUEUED / 调度中心排队</span>
              <div class="phase-detail" v-if="traceData.queueLatency">排队延迟: <b :style="{ color: traceData.queueLatency > 2000 ? '#ff4949' : '#e6a23c' }">{{ traceData.queueLatency }} ms</b></div>
            </el-timeline-item>

            <el-timeline-item center type="primary" :timestamp="formatDate(traceData.dispatchTime)">
              <span class="phase-title">DISPATCHED / 边缘节点计算分配</span>
              <div class="phase-detail">Target: <b style="color: #00ffcc">{{ traceData.assignedUavId || 'UNKNOWN' }}</b></div>
            </el-timeline-item>
            
            <el-timeline-item center type="primary" v-if="traceData.txLatency" :timestamp="formatDate(traceData.executionStartTime)">
              <span class="phase-title">TRANSMITTING / 数据流光束送达</span>
              <div class="phase-detail">传输/寻址延时: <b style="color: #3b82f6">{{ traceData.txLatency }} ms</b></div>
            </el-timeline-item>

            <el-timeline-item center type="success" :timestamp="formatDate(traceData.executionEndTime)">
              <span class="phase-title">COMPUTING_FINISHED / 节点计算完成</span>
              <div class="phase-detail" v-if="traceData.computeLatency">核心计算耗时: <b style="color: #13ce66">{{ traceData.computeLatency }} ms</b></div>
            </el-timeline-item>
          </el-timeline>

          <!-- 瀑布流指标汇总提取区块 Metric Summary Block -->
          <div class="trace-summary">
             <div class="summ-item">
                 <div class="l">TOTAL LATENCY</div>
                 <div class="v" style="color: #fff">{{ traceData.queueLatency + traceData.txLatency + traceData.computeLatency }} ms</div>
             </div>
             <div class="summ-item">
                 <div class="l">NODE ALLOCATED</div>
                 <div class="v" style="color: #00ffcc">{{ traceData.assignedUavId || 'UNKNOWN' }}</div>
             </div>
          </div>

          <!-- 可视化耗时瀑布图 Waterfall Chart -->
          <div class="waterfall-container" v-if="traceData.queueLatency || traceData.txLatency || traceData.computeLatency">
              <div class="w-label">EXECUTION WATERFALL PROFILE</div>
              <div class="w-bar-bg">
                 <el-tooltip :content="`排队 等待: ${traceData.queueLatency}ms`" placement="top">
                    <div class="w-segment w-queue" :style="{ width: getPercentage(traceData.queueLatency) + '%' }"></div>
                 </el-tooltip>
                 <el-tooltip :content="`网络 传输: ${traceData.txLatency}ms`" placement="top">
                    <div class="w-segment w-tx" :style="{ width: getPercentage(traceData.txLatency) + '%' }"></div>
                 </el-tooltip>
                 <el-tooltip :content="`边缘 计算: ${traceData.computeLatency}ms`" placement="top">
                    <div class="w-segment w-compute" :style="{ width: getPercentage(traceData.computeLatency) + '%' }"></div>
                 </el-tooltip>
              </div>
              <div class="w-legend">
                 <span><span class="dot" style="background:#e6a23c"></span>Queue ({{ getPercentage(traceData.queueLatency) }}%)</span>
                 <span><span class="dot" style="background:#3b82f6"></span>Transmit ({{ getPercentage(traceData.txLatency) }}%)</span>
                 <span><span class="dot" style="background:#13ce66"></span>Compute ({{ getPercentage(traceData.computeLatency) }}%)</span>
              </div>
          </div>
       </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search, Loading } from '@element-plus/icons-vue'
import { api } from '../services/api'
import { useAppStore } from '../store/appStore'
import { useTaskStore } from '../store/taskStore'
import { ElMessage } from 'element-plus'

const appStore = useAppStore()
const taskStore = useTaskStore()

const searchTaskId = ref('')
const loading = ref(false)
const drawerVisible = ref(false)
const traceData = ref<any>(null)

onMounted(async () => {
    if (taskStore.completedTasks.length === 0) {
        loading.value = true
        try {
            const res = await api.getCompletedTasks()
            if (res.data && Array.isArray(res.data)) {
                taskStore.setCompletedTasks(res.data)
            }
        } catch (e) {
            console.error('Failed to load completed tasks', e)
        } finally {
            loading.value = false
        }
    }
})

const openDrawer = (id: string) => {
    drawerVisible.value = true
    fetchTrace(id)
}

const fetchTrace = async (id: string) => {
    if (!id) return
    searchTaskId.value = id
    
    loading.value = true
    traceData.value = null // 清理脏数据，防止显示残影
    try {
        const res = await api.getTaskTraceLog(id)
        if (res.data) {
           traceData.value = res.data
        } else {
           traceData.value = null
        }
    } catch (e) {
        ElMessage.error('该负载轨迹在内网已失效')
        traceData.value = null
    } finally {
        loading.value = false
    }
}

const formatDate = (ts: number) => {
    if (!ts) return ''
    const d = new Date(ts)
    return `${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}:${d.getSeconds().toString().padStart(2,'0')}.${d.getMilliseconds().toString().padStart(3,'0')}`
}

const getPercentage = (val: number) => {
    if (!traceData.value || !val) return 0;
    const total = traceData.value.queueLatency + traceData.value.txLatency + traceData.value.computeLatency;
    if (total === 0) return 0;
    return Math.round((val / total) * 100);
}
</script>

<style scoped>
.trace-container {
    padding-bottom: 30px;
}
.search-panel {
    display: flex;
    gap: 10px;
    max-width: 600px;
}
.phase-title {
    font-weight: 600;
    font-size: 14px;
    letter-spacing: 0.5px;
    color: #c9d1d9;
}
.phase-detail {
    font-size: 13px;
    color: #8b949e;
    margin-top: 5px;
}
.trace-timeline-wrapper {
    padding: 0 10px;
}
.trace-summary {
    display: flex;
    gap: 20px;
    margin-top: 30px;
    padding: 15px;
    background: rgba(0, 0, 0, 0.2);
    border: 1px solid #30363d;
    border-radius: 4px;
}
.summ-item {
    flex: 1;
}
.summ-item .l {
    font-size: 10px;
    color: #8b949e;
    letter-spacing: 1px;
}
.summ-item .v {
    font-size: 18px;
    font-weight: bold;
    font-family: monospace;
    margin-top: 5px;
}

/* Waterfall Chart */
.waterfall-container {
    margin-top: 25px;
    padding: 15px;
    background: #f5f5f5;
    border: 1px solid #e0e0e0;
    border-radius: 6px;
}
.w-label {
    font-size: 11px;
    color: #8b949e;
    font-family: var(--font-mono);
    margin-bottom: 10px;
    letter-spacing: 0.5px;
}
.w-bar-bg {
    height: 12px;
    background: #e8e8e8;
    border-radius: 6px;
    display: flex;
    overflow: hidden;
    box-shadow: inset 0 1px 3px rgba(0,0,0,0.5);
}
.w-segment {
    height: 100%;
    transition: width 0.5s ease;
}
.w-queue { background: #e6a23c; }
.w-tx { background: #3b82f6; }
.w-compute { background: #13ce66; }
.w-legend {
    display: flex;
    justify-content: space-between;
    margin-top: 10px;
    font-size: 11px;
    color: #c9d1d9;
    font-family: var(--font-mono);
}
.w-legend .dot {
    display: inline-block;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    margin-right: 5px;
}

/* 赛博朋克深色表格主题样式注入 */
:deep(.el-table) {
  --el-table-border-color: #e0e0e0;
  --el-table-header-bg-color: #f5f5f5;
  --el-table-bg-color: #ffffff;
  --el-table-tr-bg-color: #ffffff;
  --el-table-row-hover-bg-color: #f9f9f9;
  color: #222222;
}
:deep(.el-table th.el-table__cell) {
  background-color: #f5f5f5;
}

/* 侧边分析抽屉专属定制样式 */
:deep(.cyber-drawer) {
  background-color: #fafafa !important;
  border-left: 1px solid #e0e0e0;
}
:deep(.cyber-drawer .el-drawer__header) {
  color: #111111;
  font-weight: bold;
  border-bottom: 1px solid #e0e0e0;
  margin-bottom: 0;
  padding: 15px 20px;
}

/* Glassmorphism Tags */
.glass-tag {
  display: inline-block;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: bold;
  font-family: monospace;
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.15);
  background: rgba(0, 0, 0, 0.04);
}
.tag-success {
  background: rgba(30, 132, 73, 0.12);
  color: #1e8449;
  border-color: rgba(30, 132, 73, 0.3);
}
.tag-warning {
  background: rgba(214, 137, 16, 0.12);
  color: #d68910;
  border-color: rgba(214, 137, 16, 0.3);
}
.tag-danger {
  background: rgba(192, 57, 43, 0.12);
  color: #c0392b;
  border-color: rgba(192, 57, 43, 0.3);
}
.tag-info {
  background: rgba(100, 100, 100, 0.1);
  color: #555555;
  border-color: rgba(100, 100, 100, 0.25);
}

/* Micro Progress Bar */
.micro-progress {
  width: 40px;
  height: 4px;
  background: rgba(255,255,255,0.1);
  border-radius: 2px;
  overflow: hidden;
}
.micro-progress-bar {
  height: 100%;
  width: 30%;
  background: #00ffcc;
  animation: indeterminateProgress 1.5s infinite linear;
}

@keyframes indeterminateProgress {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(400%); }
}
</style>
