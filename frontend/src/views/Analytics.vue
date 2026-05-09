<template>
  <div class="analytics-container">
    <el-card class="cyber-card control-card" shadow="always">
       <template #header>
          <div class="card-header cyber-header" style="justify-content: space-between;">
             <div>
                <span class="cyber-title" style="margin-right: 15px">多维效能基准分析 / MULTI-DIMENSIONAL BENCHMARK</span>
                <el-tag type="info" effect="dark" size="small">Data Sync: {{ appStore.performanceStats.history.length }} 批次记录</el-tag>
             </div>
             
             <div style="display: flex; align-items: center; gap: 12px;">
                 <el-radio-group v-model="currentMetric" size="small" @change="renderChart">
                   <el-radio-button label="latency">调度延迟</el-radio-button>
                   <el-radio-button label="energy">节点能耗</el-radio-button>
                   <el-radio-button label="bandwidth">带宽消耗</el-radio-button>
                   <el-radio-button label="success">成功率</el-radio-button>
                 </el-radio-group>
                 <el-button size="small" type="success" class="cyber-btn" @click="downloadCsv" :loading="exportLoading">
                   💾 导出基准数据 (CSV)
                 </el-button>
              </div>
          </div>
      </template>

      <div class="dash-grid">
         <!-- 主对比分析图表 Main Comparative Chart -->
         <div class="main-chart-panel panel-dark">
             <div class="panel-header">算法评估演进图 <span style="font-size:10px;color:#8b949e">- 支持框选缩放</span></div>
             <div ref="chartRef" class="chart-container" style="width: 100%; height: 400px; padding: 10px;"></div>
         </div>
      </div>
    </el-card>

    <br/>
    <!-- 次级辅助度量指标 Secondary Analysis -->
    <el-row :gutter="15">
       <el-col :span="12">
          <el-card class="cyber-card control-card">
              <template #header><div class="cyber-header"><span class="cyber-title">综合评价体系 / RADAR</span></div></template>
              <div ref="radarChartRef" style="height: 250px; width: 100%;"></div>
          </el-card>
       </el-col>
       <el-col :span="12">
           <el-card class="cyber-card control-card">
              <template #header><div class="cyber-header"><span class="cyber-title">资源消耗矩阵 / MATRIX</span></div></template>
              <div ref="matrixChartRef" style="height: 250px; width: 100%;"></div>
          </el-card>
       </el-col>
    </el-row>

  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts'
import { useAppStore } from '../store/appStore'
import { api } from '../services/api'
import { ElMessage } from 'element-plus'

const appStore = useAppStore()
const chartRef = ref<HTMLElement | null>(null)
const radarChartRef = ref<HTMLElement | null>(null)
const matrixChartRef = ref<HTMLElement | null>(null)

let chart: echarts.ECharts | null = null
let radarChart: echarts.ECharts | null = null
let matrixChart: echarts.ECharts | null = null

const currentMetric = ref('latency')
const exportLoading = ref(false)

const downloadCsv = async () => {
  exportLoading.value = true
  try {
    const response = await api.exportMetricsCsv()
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

const initChart = () => {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value, 'dark')
  
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(13, 17, 23, 0.95)',
      borderColor: '#30363d',
      textStyle: { color: '#c9d1d9', fontSize: 11 },
      axisPointer: { type: 'shadow' },
      formatter: (params: any) => {
        if (!params || params.length === 0) return ''
        const idx = params[0].dataIndex
        const point = appStore.performanceStats.history[idx]
        const meta = point?.meta
        let header = `<div style="font-weight:bold;margin-bottom:4px;color:#00ffcc">${point?.batch || ''}</div>`
        if (meta) {
          header += `<div style="color:#8b949e;font-size:10px;margin-bottom:6px;line-height:1.5">`
          header += `任务数: ${meta.taskCount} &nbsp;|&nbsp; 类型: ${meta.type}<br/>`
          header += `CPU: ${meta.cpu} 核 &nbsp;|&nbsp; 内存: ${meta.memory} MB &nbsp;|&nbsp; 数据: ${meta.dataSize} MB`
          header += `</div>`
        }
        let body = ''
        for (const p of params) {
          if (p.value != null) {
            body += `<div>${p.marker} ${p.seriesName}: <b>${p.value.toLocaleString()}</b></div>`
          }
        }
        return header + body
      }
    },
    legend: {
      data: ['Greedy', 'WFQ', 'Geo'],
      textStyle: { color: '#8b949e', fontSize: 11 },
      top: 0,
      right: 0,
    },
    grid: { left: 50, right: 15, bottom: 40, top: 30 },
    dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 0, height: 16, borderColor: 'transparent', textStyle: {color: 'transparent'} }],
    xAxis: {
      type: 'category',
      data: [],
      axisLine: { lineStyle: { color: '#30363d' } },
      axisLabel: { color: '#8b949e', fontSize: 9, rotate: 25, interval: 0 }
    },
    yAxis: {
      type: 'value',
      name: '延迟 (ms)',
      nameTextStyle: { color: '#8b949e', fontSize: 10 },
      splitLine: { lineStyle: { color: '#21262d' } },
      axisLabel: { color: '#8b949e', fontSize: 10 }
    },
    series: [
      { name: 'Greedy', type: 'bar', data: [], barGap: '10%', itemStyle: { color: '#f85149', borderRadius: [3, 3, 0, 0] } },
      { name: 'WFQ',    type: 'bar', data: [], itemStyle: { color: '#d29922', borderRadius: [3, 3, 0, 0] } },
      { name: 'Geo',    type: 'bar', data: [], itemStyle: { color: '#3fb950', borderRadius: [3, 3, 0, 0] } }
    ]
  }
  chart.setOption(option)
}

const renderChart = () => {
    if (!chart) return
    const metricsConfig: Record<string, { key: string, name: string }> = {
        'latency': { key: 'latency', name: '延迟 (ms)' },
        'energy': { key: 'energy', name: '总能耗 (J)' },
        'bandwidth': { key: 'bandwidth', name: '表观带宽耗' },
        'success': { key: 'success', name: '成功率 (%)' }
    }
    const conf = metricsConfig[currentMetric.value]
    
    // 从 Pinia 状态树中读取数据
    const hist = appStore.performanceStats.history
    const xData = hist.map((h: any) => h.batch)
    
    chart.setOption({
        yAxis: { name: conf?.name },
        xAxis: { data: xData },
        series: [
            { name: 'Greedy', data: hist.map((h: any) => h[conf?.key || '']?.greedy) },
            { name: 'WFQ',    data: hist.map((h: any) => h[conf?.key || '']?.wfq) },
            { name: 'Geo',    data: hist.map((h: any) => h[conf?.key || '']?.geo) }
        ]
    })

    // 初始化雷达图与矩阵图 (如果尚未初始化)
    if (!radarChart && radarChartRef.value) {
        radarChart = echarts.init(radarChartRef.value, 'dark')
        radarChart.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'item' },
            radar: {
                indicator: [
                   { name: 'Latency (Avg)', max: 10000 },
                   { name: 'Energy (Avg)', max: 50 },
                   { name: 'Bandwidth', max: 200 },
                   { name: 'Success Rate', max: 100 }
                ],
                splitArea: { show: false },
                axisName: { color: '#8b949e', fontSize: 10 },
                splitLine: { lineStyle: { color: '#30363d' } },
                axisLine: { lineStyle: { color: '#30363d' } }
            },
            series: [{
                type: 'radar',
                data: [
                    { value: [0, 0, 0, 0], name: 'Greedy', itemStyle: { color: '#f85149' } },
                    { value: [0, 0, 0, 0], name: 'WFQ', itemStyle: { color: '#d29922' } },
                    { value: [0, 0, 0, 0], name: 'Geo', itemStyle: { color: '#3fb950' } }
                ]
            }]
        })
    }

    if (!matrixChart && matrixChartRef.value) {
        matrixChart = echarts.init(matrixChartRef.value, 'dark')
        matrixChart.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
            legend: { textStyle: { fontSize: 9 } },
            grid: { top: 30, bottom: 20, left: 40, right: 10 },
            xAxis: { type: 'value', splitLine: { show: false }, axisLabel: { fontSize: 9 } },
            yAxis: { type: 'category', data: ['Greedy', 'WFQ', 'Geo'], axisLabel: { fontSize: 10 } },
            series: [
                { name: 'Total Energy (J)', type: 'bar', stack: 'total', data: [] },
                { name: 'Total Latency (s)', type: 'bar', stack: 'total', data: [] }
            ],
            color: ['#f85149', '#3b82f6']
        })
    }

    // 更新雷达与矩阵图的数据源 (若图表已就绪且历史数据不为空)
    if (radarChart && hist.length > 0) {
        const latest = hist[hist.length - 1]
        // 为雷达图归一化计算逻辑 (延迟以 ms 计算, 能耗以 J 计算, 成功率 %, 带宽 MB/s)
        const getV = (alg: string) => [
            Math.min(10000, latest.latency?.[alg] || 0),
            Math.min(50, latest.energy?.[alg] || 0),
            Math.min(200, latest.bandwidth?.[alg] || 0),
            latest.success?.[alg] || 0
        ]
        radarChart.setOption({
            series: [{
                data: [
                    { value: getV('greedy'), name: 'Greedy' },
                    { value: getV('wfq'), name: 'WFQ' },
                    { value: getV('geo'), name: 'Geo' }
                ]
            }]
        })
    }

    if (matrixChart && hist.length > 0) {
        const aggs = { greedy: { e: 0, l: 0 }, wfq: { e: 0, l: 0 }, geo: { e: 0, l: 0 } }
        hist.forEach((h: any) => {
           aggs.greedy.e += (h.energy?.greedy || 0); aggs.greedy.l += (h.latency?.greedy || 0) / 1000;
           aggs.wfq.e += (h.energy?.wfq || 0); aggs.wfq.l += (h.latency?.wfq || 0) / 1000;
           aggs.geo.e += (h.energy?.geo || 0); aggs.geo.l += (h.latency?.geo || 0) / 1000;
        })
        matrixChart.setOption({
            series: [
                { data: [aggs.greedy.e, aggs.wfq.e, aggs.geo.e] },
                { data: [aggs.greedy.l, aggs.wfq.l, aggs.geo.l] }
            ]
        })
    }
}

watch(() => appStore.performanceStats.history, () => {
    renderChart()
}, { deep: true })

onMounted(() => {
    setTimeout(() => {
        initChart()
        renderChart()
    }, 100) // 延迟确保 DOM 容器已完全渲染展开
    
    window.addEventListener('resize', handleResize)
})

const handleResize = () => {
  chart?.resize()
  radarChart?.resize()
  matrixChart?.resize()
}

onUnmounted(() => {
    chart?.dispose()
    radarChart?.dispose()
    matrixChart?.dispose()
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
}
</style>
