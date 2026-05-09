import { defineStore } from 'pinia'

export interface UAVNode {
    id: string
    name: string
    maxCpu: number
    currentCpuUsage: number
    maxMemory: number
    currentMemoryUsage: number
    networkBandwidth: number
    online: boolean
    activeTasksCount: number
    x: number
    y: number
    battery: number
    rthMode?: boolean
    charging?: boolean
}

export const useNodeStore = defineStore('node', {
    state: () => ({
        nodes: [] as UAVNode[],
    }),
    actions: {
        setNodes(nodes: UAVNode[]) {
            this.nodes = nodes
        }
    }
})
