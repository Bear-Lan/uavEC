import { defineStore } from 'pinia'

export interface CloudStats {
    queueLength: number
    processing: number
    completedTotal: number
    arrivalsTotal: number
    cpuCores: number
    serviceRate: number
    lastUpdate?: number
}

export const useCloudStore = defineStore('cloud', {
    state: () => ({
        stats: {
            queueLength: 0,
            processing: 0,
            completedTotal: 0,
            arrivalsTotal: 0,
            cpuCores: 64,
            serviceRate: 1.0,
            lastUpdate: 0
        } as CloudStats
    }),
    actions: {
        setStats(stats: CloudStats) {
            this.stats = { ...stats }
        }
    }
})