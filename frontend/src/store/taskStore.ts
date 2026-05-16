import { defineStore } from 'pinia'

export interface TaskInfo {
    id?: string
    taskName?: string
    type: string
    dataSize: number
    requiredCpu: number
    requiredMemory?: number
    priority: number
    offloadAlgorithm?: string
    schedulingAlgorithm?: string
    status?: string
    assignedUavId?: string
    submitTime?: number
    startTime?: number
    endTime?: number
    originX?: number
    originY?: number
    batchId?: string
    operatorName?: string
    customW1?: number
    customW2?: number
    customW3?: number
}

export const useTaskStore = defineStore('task', {
    state: () => ({
        activeTasks: [] as TaskInfo[],
        completedTasks: [] as TaskInfo[],
        stealEvents: [] as { id: string, fromNodeId: string, toNodeId: string, timestamp: number }[],
        performanceStats: {
            greedy: [] as number[],
            wfq: [] as number[],
            history: [] as any[]
        }
    }),
    actions: {
        addActiveTask(task: TaskInfo) {
            const index = this.activeTasks.findIndex(t => t.id === task.id)
            if (index >= 0) {
                this.activeTasks[index] = task
            } else {
                this.activeTasks.push(task)
            }
        },
        removeActiveTask(taskId: string) {
            this.activeTasks = this.activeTasks.filter(t => t.id !== taskId)
        },
        addCompletedTask(task: TaskInfo) {
            this.completedTasks.unshift(task)
            if (this.completedTasks.length > 50) {
                this.completedTasks.pop()
            }
        },
        setCompletedTasks(tasks: TaskInfo[]) {
            this.completedTasks = tasks
        },
        addStealEvent(id: string, fromNodeId: string, toNodeId: string) {
            this.stealEvents.push({ id, fromNodeId, toNodeId, timestamp: Date.now() })
        },
        removeStealEvent(id: string) {
            this.stealEvents = this.stealEvents.filter(e => e.id !== id)
        }
    }
})
