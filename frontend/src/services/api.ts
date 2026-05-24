import axios from 'axios'
import type { TaskInfo } from '../store/appStore'

const API_BASE = '/api'

// 请求拦截器：添加 Token
axios.interceptors.request.use((config) => {
    const token = sessionStorage.getItem('edg:token')
    if (token) {
        config.headers['X-Auth-Token'] = token
    }
    return config
})

export const api = {
    // 鉴权认证
    login: (username: string, password?: string) => axios.post(`${API_BASE}/auth/login`, { username, password }),
    register: (username: string, password?: string, x?: number, y?: number) => axios.post(`${API_BASE}/auth/register`, { username, password, x, y }),
    logout: (username: string) => axios.post(`${API_BASE}/auth/logout`, { username }),
    announceOnline: (username: string) => axios.post(`${API_BASE}/auth/online`, { username }),
    getOnlineUsers: () => axios.get(`${API_BASE}/auth/online-users`),
    getMe: (username: string) => axios.get(`${API_BASE}/auth/me`, { params: { username } }),
    updateProfile: (currentUsername: string, newUsername: string, x: number, y: number) =>
        axios.put(`${API_BASE}/auth/profile`, { currentUsername, newUsername, x, y }),

    // User Management (Admin)
    getUsers: () => axios.get(`${API_BASE}/users`),
    updateUserStatus: (id: string, enabled: boolean) => axios.put(`${API_BASE}/users/${id}/status`, null, { params: { enabled } }),
    deleteUser: (id: string) => axios.delete(`${API_BASE}/users/${id}`),

    // 节点信标管理
    getNodes() {
        return axios.get(`${API_BASE}/nodes`)
    },
    addNode() {
        return axios.post(`${API_BASE}/nodes/add`)
    },
    deleteNode(id: string) {
        return axios.delete(`${API_BASE}/nodes/${id}`)
    },
    setNodeStatus: (nodeId: string, online: boolean) => axios.post(`/api/nodes/${nodeId}/status?online=${online}`),
    setNodePosition: (nodeId: string, x: number, y: number) => axios.post(`/api/nodes/${nodeId}/position?x=${x}&y=${y}`),
    emergencyCharge: (nodeId: string) => axios.post(`/api/nodes/${nodeId}/charge`),
    createSnapshot: () => axios.post(`${API_BASE}/nodes/snapshot`),
    rollbackSnapshot: () => axios.post(`${API_BASE}/nodes/rollback`),

    exportMetricsCsv(batchId?: string) {
        const params = batchId ? { batchId } : {}
        return axios.get(`${API_BASE}/metrics/export`, { params, responseType: 'text' })
    },

    // 节点硬件配置
    updateNodeConfig(id: string, config: Record<string, number>) {
        return axios.put(`${API_BASE}/nodes/${id}/config`, config)
    },

    // 负载任务流 (Batch / Poisson)
    submitTask(task: TaskInfo) {
        return axios.post(`${API_BASE}/tasks`, task)
    },
    submitBatchTasks(tasks: TaskInfo[]) {
        return axios.post(`${API_BASE}/tasks/batch`, tasks)
    },
    startTraffic(params: Record<string, any>) {
        return axios.post(`${API_BASE}/traffic/start`, params)
    },
    stopTraffic() {
        return axios.post(`${API_BASE}/traffic/stop`)
    },
    getTrafficStatus() {
        return axios.get(`${API_BASE}/traffic/status`)
    },

    // 性能审计与遥测
    getBatchMetrics(batchId: string) {
        return axios.get(`${API_BASE}/metrics/${batchId}`)
    },
    getBatchDetailAnalytics(batchId: string) {
        return axios.get(`${API_BASE}/metrics/${batchId}/detail`)
    },
    getMetricsHistory() {
        return axios.get(`${API_BASE}/metrics/history`)
    },
    syncAllBatchMetrics() {
        return axios.post(`${API_BASE}/metrics/sync`)
    },

    // 分布式链路追踪
    getTaskTraceLog(taskId: string) {
        return axios.get(`${API_BASE}/tasks/${taskId}/trace`)
    },

    // 任务追踪与路由审计 - 获取已完成任务
    getCompletedTasks() {
        return axios.get(`${API_BASE}/tasks/completed`)
    },

    // 获取活跃/排队任务
    getActiveTasks() {
        return axios.get(`${API_BASE}/tasks/active`)
    }
}
