<template>
  <div class="analytics-container">
    <!-- 批次选择 + KPI 总览 -->
    <el-card class="cyber-card control-card" shadow="always">
      <template #header>
        <div class="card-header cyber-header" style="justify-content: space-between;">
          <div style="display: flex; align-items: center; gap: 12px;">
            <span class="cyber-title">多维效能基准分析 / BENCHMARK</span>
            <el-select v-model="selectedBatchId" placeholder="选择批次（最近批次优先）" size="small" style="width: 260px;" @change="onBatchSelected">
              <el-option v-for="b in availableBatches" :key="b.id" :label="b.label" :value="b.id" />
            </el-select>
            <el-switch v-if="batchDetailData && batchDetailData.status === 'PROCESSING'" v-model="autoRefresh" active-text="实时" />
          </div>
          <div style="display: flex; align-items: center; gap: 12px;">
            <el-tag type="info" effect="dark" size="small">{{ selectedBatchId || '未选择批次' }}</el-tag>
            <el-button size="small" type="success" class="cyber-btn" @click="downloadCsv" :loading="exportLoading">
              💾 导出CSV
            </el-button>
          </div>
        </div>
      </template>

      <!-- 批次级 KPI 指示器 -->
      <div class="kpi-panel" style="margin-bottom: 10px;">
        <div class="kpi-card" v-for="kpi in batchKpiCards" :key="kpi.label" :style="{ borderColor: kpi.color }">
          <div class="kpi-icon" :style="{ backgroundColor: kpi.color + '22' }">
            <span :style="{ color: kpi.color }">{{ kpi.icon }}</span>
          </div>
          <div class="kpi-content">
            <div class="kpi-label">{{ kpi.label }}</div>
            <div class="kpi-value" :style="{ color: kpi.color }">{{ kpi.value }}</div>
          </div>
        </div>
        <!-- PROCESSING 进度条 -->
        <div v-if="batchDetailData && batchDetailData.status === 'PROCESSING'" class="kpi-card" style="border-color: #58a6ff; flex: 2; min-width: 200px;">
          <div class="kpi-content" style="width: 100%;">
            <div style="display: flex; justify-content: space-between; margin-bottom: 4px;">
              <span style="color: #8b949e; font-size: 11px;">完成进度</span>
              <span style="color: #58a6ff; font-size: 11px;">{{ batchDetailData.completedTasks }}/{{ batchDetailData.totalTasks }}</span>
            </div>
            <el-progress :percentage="batchDetailData.totalTasks > 0 ? Math.round(batchDetailData.completedTasks / batchDetailData.totalTasks * 100) : 0" :stroke-width="6" :color="progressColor" style="margin-top: 4px;" />
          </div>
        </div>
      </div>

      <div v-if="!selectedBatchId" style="text-align: center; padding: 30px; color: #8b949e;">
        请从上方选择批次以查看效能分析
      </div>
    </el-card>

    <br/>
    <!-- 批次分析图表区 -->
    <div v-if="batchDetailData">
      <el-row :gutter="15" style="margin-bottom: 15px;">
        <!-- 任务延迟散点图 -->
        <el-col :span="14">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header>
              <div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">任务延迟分布（优先级 vs 延迟）</div>
            </template>
            <div ref="latencyScatterRef" style="height: 260px; width: 100%;"></div>
          </el-card>
        </el-col>
        <!-- 任务列表摘要 -->
        <el-col :span="10">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header>
              <div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">
                任务列表 ({{ taskListPreview.length }}/{{ batchDetailData.totalTasks }})
              </div>
            </template>
            <div style="max-height: 260px; overflow-y: auto;">
              <div v-for="task in taskListPreview" :key="task.id" class="task-list-item">
                <span :style="{ color: task.status === 'COMPLETED' ? '#3fb950' : task.status === 'FAILED' ? '#f85149' : '#d29922' }">
                  {{ task.status === 'COMPLETED' ? '✅' : task.status === 'FAILED' ? '❌' : '⏳' }}
                </span>
                <span style="color: #c9d1d9; font-size: 11px; margin-left: 6px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ task.name }}</span>
                <span style="color: #8b949e; font-size: 10px; margin-left: 6px;">{{ task.latency || 0 }}ms</span>
              </div>
              <div v-if="!taskListPreview.length" style="color: #8b949e; text-align: center; padding: 20px; font-size: 12px;">
                暂无任务数据
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="15" style="margin-bottom: 15px;">
        <!-- 节点任务分布 -->
        <el-col :span="8">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header><div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">节点任务分布</div></template>
            <div ref="nodeDistChartRef" style="height: 200px; width: 100%;"></div>
          </el-card>
        </el-col>
        <!-- 任务类型分布 -->
        <el-col :span="8">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header><div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">任务类型分布</div></template>
            <div ref="typeDistChartRef" style="height: 200px; width: 100%;"></div>
          </el-card>
        </el-col>
        <!-- 延迟分位数 -->
        <el-col :span="8">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header><div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">延迟分位数 (ms)</div></template>
            <div ref="percentileChartRef" style="height: 200px; width: 100%;"></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 能耗与延迟累积分布 -->
      <el-row :gutter="15">
        <el-col :span="12">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header><div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">任务能耗分布</div></template>
            <div ref="energyScatterRef" style="height: 200px; width: 100%;"></div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="panel-dark" style="background: #06080a; border: 1px solid #21262d;">
            <template #header><div style="font-size: 12px; color: #c9d1d9; font-weight: 600;">节点完成时序</div></template>
            <div ref="timelineChartRef" style="height: 200px; width: 100%;"></div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, computed, nextTick } from 'vue'
import * as echarts from 'echarts'
import { api } from '../services/api'
import { ElMessage } from 'element-plus'

const exportLoading = ref(false)

// ===================== 批次详情分析 =====================
const selectedBatchId = ref('')
const batchLoading = ref(false)
const batchDetailData = ref<any>(null)
const autoRefresh = ref(false)

interface BatchOption { id: string; label: string }
const availableBatches = ref<BatchOption[]>([])

let nodeDistChart: echarts.ECharts | null = null
let typeDistChart: echarts.ECharts | null = null
let percentileChart: echarts.ECharts | null = null
let latencyScatterChart: echarts.ECharts | null = null
let energyScatterChart: echarts.ECharts | null = null
let timelineChart: echarts.ECharts | null = null
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null

const nodeDistChartRef = ref<HTMLElement | null>(null)
const typeDistChartRef = ref<HTMLElement | null>(null)
const percentileChartRef = ref<HTMLElement | null>(null)
const latencyScatterRef = ref<HTMLElement | null>(null)
const energyScatterRef = ref<HTMLElement | null>(null)
const timelineChartRef = ref<HTMLElement | null>(null)

const progressColor = computed(() => {
    if (!batchDetailData.value) return '#58a6ff'
    const pct = batchDetailData.value.totalTasks > 0
        ? batchDetailData.value.completedTasks / batchDetailData.value.totalTasks : 0
    if (pct >= 1) return '#3fb950'
    if (pct >= 0.5) return '#d29922'
    return '#f85149'
})

const taskListPreview = computed(() => {
    if (!batchDetailData.value?.taskList) return []
    return batchDetailData.value.taskList.slice(0, 30)
})

const batchKpiCards = computed(() => {
    const agg = batchDetailData.value?.aggregate || {}
    return [
        {
            label: '平均任务处理时长',
            icon: '⏱️',
            value: agg.avgLatency ? `${Math.round(Number(agg.avgLatency))} ms` : '--',
            color: '#58a6ff'
        },
        {
            label: '总能耗',
            icon: '⚡',
            value: agg.totalEnergy ? `${Math.round(Number(agg.totalEnergy))} J` : '--',
            color: '#d29922'
        },
        {
            label: '成功率',
            icon: '📊',
            value: agg.successRate != null ? `${Number(agg.successRate).toFixed(1)} %` : '--',
            color: agg.successRate >= 80 ? '#3fb950' : '#f85149'
        },
        {
            label: '总任务',
            icon: '🚀',
            value: batchDetailData.value?.totalTasks ? `${batchDetailData.value.totalTasks} 个` : '--',
            color: '#a371f7'
        }
    ]
})

const onBatchSelected = async () => {
    if (!selectedBatchId.value) {
        batchDetailData.value = null
        return
    }
    await loadBatchDetail()
}

const loadBatchDetail = async () => {
    if (!selectedBatchId.value) return
    batchLoading.value = true
    try {
        const resp = await api.getBatchDetailAnalytics(selectedBatchId.value)
        batchDetailData.value = resp.data
        await nextTick()
        initBatchCharts()
        renderBatchCharts()
    } catch (e) {
        console.error('Failed to load batch detail', e)
        ElMessage.error('加载批次详情失败')
    } finally {
        batchLoading.value = false
    }
}

const initBatchCharts = () => {
    if (nodeDistChartRef.value && !nodeDistChart) {
        nodeDistChart = echarts.init(nodeDistChartRef.value, 'dark')
    }
    if (typeDistChartRef.value && !typeDistChart) {
        typeDistChart = echarts.init(typeDistChartRef.value, 'dark')
    }
    if (percentileChartRef.value && !percentileChart) {
        percentileChart = echarts.init(percentileChartRef.value, 'dark')
    }
    if (latencyScatterRef.value && !latencyScatterChart) {
        latencyScatterChart = echarts.init(latencyScatterRef.value, 'dark')
    }
    if (energyScatterRef.value && !energyScatterChart) {
        energyScatterChart = echarts.init(energyScatterRef.value, 'dark')
    }
    if (timelineChartRef.value && !timelineChart) {
        timelineChart = echarts.init(timelineChartRef.value, 'dark')
    }
}

const renderBatchCharts = () => {
    if (!batchDetailData.value) return
    const data = batchDetailData.value

    // 节点分布饼图
    if (nodeDistChart && data.nodeDistribution) {
        const nodes = Object.entries(data.nodeDistribution)
        nodeDistChart.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
            series: [{
                type: 'pie',
                radius: ['35%', '65%'],
                data: nodes.map(([name, value], i) => ({
                    name, value,
                    itemStyle: { color: ['#f85149', '#d29922', '#3fb950', '#58a6ff', '#a371f7'][i % 5] }
                }))
            }]
        })
    }

    // 类型分布柱状图
    if (typeDistChart && data.typeDistribution) {
        const types = Object.entries(data.typeDistribution)
        typeDistChart.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis' },
            grid: { top: 8, bottom: 8, left: 5, right: 5 },
            xAxis: { type: 'category', data: types.map(t => t[0]), axisLabel: { fontSize: 9, color: '#8b949e' } },
            yAxis: { type: 'value', splitLine: { show: false }, axisLabel: { fontSize: 9, color: '#8b949e' } },
            series: [{ type: 'bar', data: types.map(t => t[1]), itemStyle: { color: '#3b82f6' } }]
        })
    }

    // 延迟分位数
    if (percentileChart && data.percentile) {
        const p = data.percentile
        percentileChart.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis' },
            grid: { top: 8, bottom: 8, left: 5, right: 5 },
            xAxis: { type: 'category', data: ['p50', 'p90', 'p99'], axisLabel: { fontSize: 10, color: '#8b949e' } },
            yAxis: { type: 'value', splitLine: { lineStyle: { color: '#21262d' } }, axisLabel: { fontSize: 9, color: '#8b949e' } },
            series: [{
                type: 'bar',
                data: [p.p50 || 0, p.p90 || 0, p.p99 || 0],
                itemStyle: { color: (params: any) => ['#3fb950', '#d29922', '#f85149'][params.dataIndex] }
            }]
        })
    }

    // 延迟散点图（优先级 vs 延迟）
    if (latencyScatterChart && data.taskList) {
        const scatterData = data.taskList
            .filter((t: any) => t.latency > 0)
            .map((t: any) => ({
                value: [t.priority || 0, t.latency, t.name || t.id],
                itemStyle: { color: t.status === 'COMPLETED' ? '#3fb950' : t.status === 'FAILED' ? '#f85149' : '#d29922' }
            }))
        latencyScatterChart.setOption({
            backgroundColor: 'transparent',
            tooltip: {
                trigger: 'item',
                formatter: (params: any) => `${params.data.name}<br/>优先级: ${params.data.value[0]}<br/>延迟: ${params.data.value[1]} ms`
            },
            grid: { top: 10, bottom: 30, left: 50, right: 15 },
            xAxis: { type: 'value', name: '优先级', nameTextStyle: { fontSize: 10, color: '#8b949e' }, splitLine: { lineStyle: { color: '#21262d' } }, axisLabel: { fontSize: 9, color: '#8b949e' } },
            yAxis: { type: 'value', name: '延迟 (ms)', nameTextStyle: { fontSize: 10, color: '#8b949e' }, splitLine: { lineStyle: { color: '#21262d' } }, axisLabel: { fontSize: 9, color: '#8b949e' } },
            series: [{ type: 'scatter', symbolSize: 8, data: scatterData }]
        })
    }

    // 能耗散点图（数据大小 vs 能耗）
    if (energyScatterChart && data.taskList) {
        const scatterData = data.taskList
            .filter((t: any) => t.energy > 0)
            .map((t: any) => ({
                value: [t.dataSize || 0, t.energy, t.name || t.id],
                itemStyle: { color: t.status === 'COMPLETED' ? '#3fb950' : t.status === 'FAILED' ? '#f85149' : '#d29922' }
            }))
        energyScatterChart.setOption({
            backgroundColor: 'transparent',
            tooltip: {
                trigger: 'item',
                formatter: (params: any) => `${params.data.name}<br/>数据: ${params.data.value[0]} MB<br/>能耗: ${params.data.value[1]} J`
            },
            grid: { top: 10, bottom: 30, left: 50, right: 15 },
            xAxis: { type: 'value', name: '数据大小 (MB)', nameTextStyle: { fontSize: 10, color: '#8b949e' }, splitLine: { lineStyle: { color: '#21262d' } }, axisLabel: { fontSize: 9, color: '#8b949e' } },
            yAxis: { type: 'value', name: '能耗 (J)', nameTextStyle: { fontSize: 10, color: '#8b949e' }, splitLine: { lineStyle: { color: '#21262d' } }, axisLabel: { fontSize: 9, color: '#8b949e' } },
            series: [{ type: 'scatter', symbolSize: 8, data: scatterData }]
        })
    }

    // 节点完成时序（每个节点的任务完成时间线）
    if (timelineChart && data.taskList) {
        const completedTasks = data.taskList.filter((t: any) => t.status === 'COMPLETED' && t.endTime > 0 && t.submitTime > 0)
        if (completedTasks.length > 0) {
            const baseTime = Math.min(...completedTasks.map((t: any) => t.submitTime))
            const timelineData = completedTasks.map((t: any) => ({
                name: t.name || t.id,
                value: [t.nodeId || 'UNASSIGNED', t.endTime - t.submitTime, t.endTime - baseTime],
                itemStyle: { color: '#3fb950' }
            }))
            timelineChart.setOption({
                backgroundColor: 'transparent',
                tooltip: {
                    trigger: 'item',
                    formatter: (params: any) => `${params.data.name}<br/>节点: ${params.data.value[0]}<br/>延迟: ${params.data.value[1]} ms`
                },
                grid: { top: 10, bottom: 30, left: 80, right: 15 },
                xAxis: { type: 'value', name: '相对时间 (ms)', nameTextStyle: { fontSize: 10, color: '#8b949e' }, splitLine: { lineStyle: { color: '#21262d' } }, axisLabel: { fontSize: 9, color: '#8b949e' } },
                yAxis: { type: 'category', data: [...new Set(completedTasks.map((t: any) => t.nodeId || 'UNASSIGNED'))], axisLabel: { fontSize: 9, color: '#8b949e' } },
                series: [{ type: 'scatter', symbolSize: 12, data: timelineData }]
            })
        }
    }
}

// 自动刷新
watch(autoRefresh, (val) => {
    if (val && selectedBatchId.value) {
        autoRefreshTimer = setInterval(() => {
            if (batchDetailData.value?.status === 'PROCESSING') {
                loadBatchDetail()
            } else {
                autoRefresh.value = false
                if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null }
            }
        }, 3000)
    } else {
        if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null }
    }
})

// 同步历史数据并填充批次下拉选项
const loadMetricsHistory = async () => {
    try {
        await api.syncAllBatchMetrics()
        const resp = await api.getMetricsHistory()
        if (resp.data && Array.isArray(resp.data) && resp.data.length > 0) {
            // 收集所有批次（按 batchId 分组，保持去重）
            const batchMap = new Map<string, BatchOption>()
            resp.data.forEach((item: any) => {
                const batchId = item.batchId
                if (batchId && !batchMap.has(batchId)) {
                    batchMap.set(batchId, {
                        id: batchId,
                        label: `${batchId} (${item.taskCount || '?'}任务)`
                    })
                }
            })
            // 按 batchId 时间戳倒序（最新的在前）
            availableBatches.value = Array.from(batchMap.values()).reverse()
            // 自动选中最新的批次
            if (availableBatches.value.length > 0 && !selectedBatchId.value) {
                const first = availableBatches.value[0]
                if (first) {
                    selectedBatchId.value = first.id
                    await loadBatchDetail()
                }
            }
        }
    } catch (e) {
        console.error('Failed to load metrics history', e)
    }
}

const downloadCsv = async () => {
  exportLoading.value = true
  try {
    const response = await api.exportMetricsCsv(selectedBatchId.value || undefined)
    const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `uav_benchmark_${Date.now()}.csv`)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success({ message: '📊 基准数据集已成功导出！', duration: 3000 })
  } catch (e) {
    ElMessage.error('导出失败，请确认后端服务运行正常')
  } finally {
    exportLoading.value = false
  }
}

const handleResize = () => {
  nodeDistChart?.resize()
  typeDistChart?.resize()
  percentileChart?.resize()
  latencyScatterChart?.resize()
  energyScatterChart?.resize()
  timelineChart?.resize()
}

onMounted(() => {
    loadMetricsHistory()
    window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
    nodeDistChart?.dispose()
    typeDistChart?.dispose()
    percentileChart?.dispose()
    latencyScatterChart?.dispose()
    energyScatterChart?.dispose()
    timelineChart?.dispose()
    if (autoRefreshTimer) clearInterval(autoRefreshTimer)
    window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.analytics-container {
   padding-bottom: 20px;
}
.panel-dark {
   background: #06080a;
   border: 1px solid #21262d;
   border-radius: 6px;
}
.panel-header {
   padding: 10px 15px;
   font-size: 13px;
   font-weight: 600;
   color: #c9d1d9;
   border-bottom: 1px solid #21262d;
   letter-spacing: 0.5px;
   display: flex;
   align-items: center;
}
.kpi-panel {
   display: flex;
   gap: 12px;
   flex-wrap: wrap;
}
.kpi-card {
   display: flex;
   align-items: center;
   gap: 12px;
   padding: 12px 16px;
   background: #0d1117;
   border: 1px solid #30363d;
   border-radius: 8px;
   min-width: 180px;
   flex: 1;
   transition: all 0.3s;
}
.kpi-card:hover {
   background: #161b22;
}
.kpi-icon {
   width: 40px;
   height: 40px;
   border-radius: 8px;
   display: flex;
   align-items: center;
   justify-content: center;
   font-size: 20px;
}
.kpi-content {
   flex: 1;
}
.kpi-label {
   font-size: 11px;
   color: #8b949e;
   margin-bottom: 2px;
}
.kpi-value {
   font-size: 18px;
   font-weight: 700;
   font-family: 'JetBrains Mono', monospace;
}
.kpi-status {
   font-size: 10px;
   padding: 2px 8px;
   border-radius: 10px;
}
.status-ok {
   background: rgba(63, 185, 80, 0.15);
   color: #3fb950;
}
.status-danger {
   background: rgba(248, 81, 73, 0.15);
   color: #f85149;
   animation: pulse-danger 1.5s infinite;
}
.status-warning {
   background: rgba(210, 153, 34, 0.15);
   color: #d29922;
}
@keyframes pulse-danger {
   0%, 100% { opacity: 1; }
   50% { opacity: 0.5; }
}
</style>
