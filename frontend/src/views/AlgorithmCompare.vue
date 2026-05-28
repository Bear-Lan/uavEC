<template>
  <div class="compare-container">
    <div class="page-header">
      <h2 class="page-title">Algorithm Comparison / 全算法效能对比</h2>
    </div>

    <div v-if="!hasData" class="empty-state">
      <p>No data available. Please run the benchmark first.</p>
    </div>

    <!-- Two-column layout: bar chart + radar chart -->
    <div v-if="hasData" class="charts-row">
      <!-- Left: grouped bar chart -->
      <div class="chart-card">
        <div class="chart-title">Latency vs Energy by Algorithm</div>
        <div ref="barChartRef" class="chart-area"></div>
      </div>
      <!-- Right: five-dimension radar -->
      <div class="chart-card">
        <div class="chart-title">Five-Dimensional Performance Radar (Normalized 0-100)</div>
        <div ref="radarChartRef" class="chart-area"></div>
      </div>
    </div>

    <!-- Comparison table -->
    <div v-if="hasData" class="table-card">
      <div class="chart-title">Algorithm Performance Summary</div>
      <table class="data-table">
        <thead>
          <tr>
            <th>Algorithm</th>
            <th>Task Count</th>
            <th>Avg Latency (ms)</th>
            <th>Avg Energy (J)</th>
            <th>Avg Bandwidth (KB)</th>
            <th>Success Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in algorithmRows" :key="row.algorithm">
            <td class="algo-name">{{ row.algorithm }}</td>
            <td>{{ row.taskCount }}</td>
            <td>{{ row.avgLatency }}</td>
            <td>{{ row.avgEnergy }}</td>
            <td>{{ row.avgBandwidth }}</td>
            <td :class="row.avgSuccessRate >= 80 ? 'success-high' : 'success-low'">
              {{ row.avgSuccessRate.toFixed(1) }}%
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { api } from '../services/api'

const loading = ref(false)
const rawData = ref<any[]>([])
let barChart: echarts.ECharts | null = null
let radarChart: echarts.ECharts | null = null
const barChartRef = ref<HTMLElement | null>(null)
const radarChartRef = ref<HTMLElement | null>(null)

interface AlgoRow {
  algorithm: string
  taskCount: number
  avgLatency: number
  avgEnergy: number
  avgBandwidth: number
  avgSuccessRate: number
  latencyCV: number
}

const algMap: Record<string, string> = {
  greedy: 'Greedy', wfq: 'WFQ', geo: 'Geo',
  custom: 'Custom', latency: 'Latency-Optimal', energy: 'Energy-Optimal',
  adaptive: 'Adaptive', dqn: 'DQN', abc: 'ABC'
}

// Statistics helpers
const mean = (arr: number[]) => arr.reduce((a, b) => a + b, 0) / arr.length
const std = (arr: number[]) => {
  const m = mean(arr)
  return Math.sqrt(arr.reduce((a, b) => a + (b - m) ** 2, 0) / arr.length)
}

const hasData = computed(() => rawData.value.length > 0)

const algorithmRows = computed<AlgoRow[]>(() => {
  const map = new Map<string, { latencies: number[], energies: number[], bws: number[], successes: number[], tasks: number[], latencyValues: number[] }>()

  rawData.value.forEach((item: any) => {
    const alg = (item.algorithm || '').toLowerCase()
    if (!alg) return
    if (!map.has(alg)) {
      map.set(alg, { latencies: [], energies: [], bws: [], successes: [], tasks: [], latencyValues: [] })
    }
    const entry = map.get(alg)!
    if (item.latency > 0) entry.latencies.push(item.latency)
    if (item.energy > 0) entry.energies.push(item.energy)
    if (item.bandwidth > 0) entry.bws.push(item.bandwidth)
    if (item.successRate != null) entry.successes.push(item.successRate)
    if (item.taskCount != null) entry.tasks.push(item.taskCount)
    if (item.latency > 0) entry.latencyValues.push(item.latency)
  })

  return Array.from(map.entries()).map(([alg, d]) => {
    const avgLatency = d.latencies.length ? Math.round(d.latencies.reduce((a, b) => a + b, 0) / d.latencies.length) : 0
    const avgEnergy = d.energies.length ? Math.round(d.energies.reduce((a, b) => a + b, 0) / d.energies.length) : 0
    const avgBandwidth = d.bws.length ? Math.round(d.bws.reduce((a, b) => a + b, 0) / d.bws.length) : 0
    const avgSuccessRate = d.successes.length ? d.successes.reduce((a, b) => a + b, 0) / d.successes.length : 0
    const totalTasks = d.tasks.reduce((a, b) => a + b, 0)
    const cv = d.latencyValues.length > 1
      ? Math.round((std(d.latencyValues) / (mean(d.latencyValues) || 1)) * 100) / 100
      : 0
    return {
      algorithm: algMap[alg] || alg,
      taskCount: totalTasks,
      avgLatency,
      avgEnergy,
      avgBandwidth,
      avgSuccessRate,
      latencyCV: cv
    } as AlgoRow
  })
})

const loadData = async () => {
  loading.value = true
  try {
    await api.syncAllBatchMetrics()
    const resp = await api.getMetricsHistory()
    rawData.value = resp.data || []
    await nextTick()
    initCharts()
    renderCharts()
  } catch (e) {
    console.error('Failed to load algorithm comparison data', e)
  } finally {
    loading.value = false
  }
}

const initCharts = () => {
  if (barChartRef.value && !barChart) barChart = echarts.init(barChartRef.value)
  if (radarChartRef.value && !radarChart) radarChart = echarts.init(radarChartRef.value)
}

const renderCharts = () => {
  if (!barChart || !radarChart || !algorithmRows.value.length) return

  const rows = algorithmRows.value
  const algorithms = rows.map(r => r.algorithm)
  const latencyData = rows.map(r => r.avgLatency)
  const energyData = rows.map(r => r.avgEnergy)

  // Left: grouped bar chart
  barChart.setOption({
    backgroundColor: '#ffffff',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: {
      data: ['Avg Latency (ms)', 'Avg Energy (J)'],
      top: 5
    },
    grid: { top: 35, bottom: 30, left: 60, right: 20 },
    xAxis: {
      type: 'category',
      data: algorithms,
      axisLabel: { fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value', name: 'Latency', nameTextStyle: { fontSize: 10 },
        splitLine: { lineStyle: { color: '#e0e0e0' } }
      },
      {
        type: 'value', name: 'Energy', nameTextStyle: { fontSize: 10 }, splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'Avg Latency (ms)', type: 'bar',
        data: latencyData,
        itemStyle: { color: '#4a90d9' }
      },
      {
        name: 'Avg Energy (J)', type: 'bar',
        data: energyData,
        yAxisIndex: 1,
        itemStyle: { color: '#e07b39' }
      }
    ]
  })

  // Right: five-dimension radar
  const maxLat = Math.max(...rows.map(r => r.avgLatency), 1)
  const maxEne = Math.max(...rows.map(r => r.avgEnergy), 1)
  const maxBw = Math.max(...rows.map(r => r.avgBandwidth), 1)
  const maxCV = Math.max(...rows.map(r => r.latencyCV), 0.01)

  const radarIndicators = rows.map(r => {
    const latencyScore = maxLat > 0 ? (1 - r.avgLatency / maxLat) * 100 : 0
    const energyScore = maxEne > 0 ? (1 - r.avgEnergy / maxEne) * 100 : 0
    const bwScore = maxBw > 0 ? (1 - r.avgBandwidth / maxBw) * 100 : 0
    const stabilityScore = maxCV > 0 ? (1 - r.latencyCV / maxCV) * 100 : 0
    const successScore = r.avgSuccessRate
    return [
      Math.round(Math.max(0, Math.min(100, latencyScore))),
      Math.round(Math.max(0, Math.min(100, energyScore))),
      Math.round(Math.max(0, Math.min(100, bwScore))),
      Math.round(Math.max(0, Math.min(100, stabilityScore))),
      Math.round(Math.max(0, Math.min(100, successScore)))
    ]
  })

  const radarColors = ['#4a90d9', '#e07b39', '#50c875', '#9b59b6', '#e74c3c', '#1abc9c', '#f39c12', '#34495e']

  radarChart.setOption({
    backgroundColor: '#ffffff',
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        const r = rows[params.dataIndex]
        return `${r?.algorithm}<br/>Latency: ${params.value[0]}<br/>Energy: ${params.value[1]}<br/>Bandwidth: ${params.value[2]}<br/>Stability: ${params.value[3]}<br/>Success: ${params.value[4]}`
      }
    },
    legend: {
      data: algorithms,
      top: 5,
      type: 'scroll'
    },
    radar: {
      indicator: [
        { name: 'Latency', max: 100 },
        { name: 'Energy', max: 100 },
        { name: 'Bandwidth', max: 100 },
        { name: 'Stability', max: 100 },
        { name: 'Success', max: 100 }
      ],
      splitNumber: 4,
      axisName: { fontSize: 10 },
      splitLine: { lineStyle: { color: '#e0e0e0' } },
      splitArea: { areaStyle: { color: ['#fafafa', '#f0f0f0'] } },
      axisLine: { lineStyle: { color: '#cccccc' } }
    },
    series: [{
      type: 'radar',
      data: rows.map((r, i) => ({
        value: radarIndicators[i],
        name: r.algorithm,
        lineStyle: { color: radarColors[i % radarColors.length] },
        areaStyle: { color: radarColors[i % radarColors.length] + '55' },
        itemStyle: { color: radarColors[i % radarColors.length] }
      }))
    }]
  })
}

const handleResize = () => {
  barChart?.resize()
  radarChart?.resize()
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  barChart?.dispose()
  radarChart?.dispose()
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
@media print {
  .compare-container {
    background: #ffffff !important;
    color: #111111 !important;
  }
  .chart-card, .table-card {
    background: #ffffff !important;
    border: 1px solid #cccccc !important;
    box-shadow: none !important;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }
  .page-title, .chart-title {
    color: #111111 !important;
  }
  .data-table th {
    background: #f0f0f0 !important;
    color: #111111 !important;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }
  .data-table td {
    color: #222222 !important;
  }
  .success-high { color: #1a7a3c !important; }
  .success-low { color: #c0392b !important; }
  .empty-state { color: #555555 !important; }
}

.compare-container {
  padding: 20px;
  background: #ffffff;
  min-height: calc(100vh - 82px);
  color: #222222;
  font-family: 'Segoe UI', 'Times New Roman', serif;
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #111111;
  margin: 0;
  letter-spacing: 0.5px;
}

.empty-state {
  text-align: center;
  padding: 60px;
  color: #666666;
}

.charts-row {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}

.chart-card {
  flex: 1;
  background: #ffffff;
  border: 1px solid #dddddd;
  border-radius: 6px;
  padding: 12px;
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: #111111;
  margin-bottom: 10px;
}

.chart-area {
  height: 280px;
  width: 100%;
}

.table-card {
  background: #ffffff;
  border: 1px solid #dddddd;
  border-radius: 6px;
  padding: 12px;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th {
  background: #f5f5f5;
  color: #111111;
  font-weight: 600;
  text-align: left;
  padding: 8px 12px;
  border-bottom: 2px solid #cccccc;
}

.data-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #eeeeee;
  color: #333333;
}

.data-table tr:last-child td {
  border-bottom: none;
}

.data-table tr:hover td {
  background: #f9f9f9;
}

.algo-name {
  font-weight: 600;
  color: #000000;
}

.success-high {
  color: #27ae60;
  font-weight: 600;
}

.success-low {
  color: #c0392b;
  font-weight: 600;
}
</style>