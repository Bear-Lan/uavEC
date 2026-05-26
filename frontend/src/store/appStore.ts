import { defineStore } from 'pinia'
import { useAuthStore } from './authStore'
import { useNodeStore, type UAVNode } from './nodeStore'
import { useTaskStore, type TaskInfo } from './taskStore'
import { useCloudStore, type CloudStats } from './cloudStore'
import { useWsStore } from './wsStore'

export type { TaskInfo, UAVNode, CloudStats }

export const useAppStore = defineStore('app', {
    state: () => ({
        systemStartTime: Date.now()
    }),
    getters: {
        nodes: () => useNodeStore().nodes,
        activeTasks: () => useTaskStore().activeTasks,
        completedTasks: () => useTaskStore().completedTasks,
        stealEvents: () => useTaskStore().stealEvents,
        performanceStats: () => useTaskStore().performanceStats,
        user: () => useAuthStore().user,
        wsConnected: () => useWsStore().wsConnected,
        stompClient: () => useWsStore().stompClient,
        cloudStats: () => useCloudStore().stats,
    },
    actions: {
        // Delegate to Auth
        async login(u: string, p?: string) { return useAuthStore().login(u, p) },
        async register(u: string, p?: string, x?: number, y?: number) { return useAuthStore().register(u, p, x, y) },
        logout() {
            useWsStore().disconnect()
            useAuthStore().logout()
        },
        async restoreSession() { return useAuthStore().restoreSession() },
        async updateUser(n: string, x: number, y: number) { return useAuthStore().updateUser(n, x, y) },

        // Delegate to Nodes
        setNodes(nodes: any[]) { useNodeStore().setNodes(nodes) },

        // Delegate to Tasks
        addActiveTask(task: any) { useTaskStore().addActiveTask(task) },
        removeActiveTask(id: string) { useTaskStore().removeActiveTask(id) },
        addCompletedTask(task: any) { useTaskStore().addCompletedTask(task) },
        addStealEvent(id: string, from: string, to: string) { useTaskStore().addStealEvent(id, from, to) },
        removeStealEvent(id: string) { useTaskStore().removeStealEvent(id) },

        // Delegate to Cloud
        setCloudStats(stats: any) { useCloudStore().setStats(stats) },

        // Delegate to WS
        initWebSocket() { useWsStore().initWebSocket() }
    }
})
