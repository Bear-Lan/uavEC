import { defineStore } from 'pinia'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { ElNotification } from 'element-plus'
import { useNodeStore } from './nodeStore'
import { useTaskStore } from './taskStore'
import { useAuthStore } from './authStore'
import { useCloudStore } from './cloudStore'
import { api } from '../services/api'

export interface OnlineUser {
    username: string
    x: number
    y: number
    loginTime: number
}

export const useWsStore = defineStore('ws', {
    state: () => ({
        stompClient: null as Client | null,
        wsConnected: false,
    }),
    actions: {
        initWebSocket() {
            if (this.stompClient && this.stompClient.active) return;

            const nodeStore = useNodeStore()
            const taskStore = useTaskStore()
            const authStore = useAuthStore()

            this.stompClient = new Client({
                webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
                reconnectDelay: 5000,
                heartbeatIncoming: 4000,
                heartbeatOutgoing: 4000,
            });

            this.stompClient.onConnect = (frame) => {
                console.log('Connected to Edge Grid WebSocket: ' + frame);
                this.wsConnected = true;

                // 连接建立后主动拉取活跃任务（刷新页面后恢复状态）
                api.getActiveTasks().then(resp => {
                    console.log('[WS] Active tasks loaded:', resp.data?.length);
                    if (resp.data && Array.isArray(resp.data)) {
                        resp.data.forEach((task: any) => {
                            if (task.status === 'RUNNING_EDGE' || task.status === 'RUNNING_CLOUD' || task.status === 'RUNNING_SPLIT') {
                                taskStore.addActiveTask(task);
                            } else if (task.status === 'QUEUED' || task.status === 'DISPATCHING') {
                                taskStore.addActiveTask(task);
                            }
                        });
                    }
                }).catch(err => {
                    console.error('[WS] Failed to fetch active tasks:', err.response?.status, err.response?.data);
                });

                // 连接建立后主动拉取节点列表（初始化时恢复节点状态）
                api.getNodes().then(resp => {
                    console.log('[WS] Nodes loaded:', resp.data);
                    if (resp.data && Array.isArray(resp.data)) {
                        nodeStore.setNodes(resp.data);
                    }
                }).catch(err => {
                    console.error('[WS] Failed to fetch nodes:', err.response?.status, err.response?.data);
                });

                this.stompClient?.subscribe('/topic/nodes', (msg) => {
                    if (msg.body) {
                        try {
                            const nodes = JSON.parse(msg.body);
                            nodeStore.setNodes(nodes);
                        } catch (e) { console.error('WS Node parse error', e) }
                    }
                });

                this.stompClient?.subscribe('/topic/tasks', (msg) => {
                    if (msg.body) {
                        try {
                            const task = JSON.parse(msg.body);
                            if (task.status === 'RUNNING_EDGE' || task.status === 'RUNNING_CLOUD' || task.status === 'RUNNING_SPLIT') {
                                taskStore.addActiveTask(task);
                            } else if (task.status === 'COMPLETED' || task.status === 'FAILED') {
                                taskStore.removeStealEvent(task.id);
                                taskStore.removeActiveTask(task.id);
                                taskStore.addCompletedTask(task);
                            } else if (task.status === 'QUEUED' || task.status === 'DISPATCHING') {
                                taskStore.addActiveTask(task);
                            }
                        } catch (e) { console.error('WS Task parse error', e) }
                    }
                });

                this.stompClient?.subscribe('/topic/notifications', (msg) => {
                    if (msg.body) {
                        try {
                            const notif = JSON.parse(msg.body);
                            if (notif.type === 'FAULT_RECOVERY') {
                                ElNotification({
                                    title: '⚡ 容错恢复触发',
                                    message: notif.message,
                                    type: 'warning',
                                    duration: 8000
                                });
                            } else if (notif.type === 'WORK_STEALING') {
                                taskStore.addStealEvent(notif.taskId || Date.now().toString(), notif.fromNodeId, notif.toNodeId);
                                ElNotification({
                                    title: '⚡ 工作窃取触发',
                                    message: notif.message,
                                    type: 'success',
                                    duration: 4000
                                });
                            }
                        } catch (e) { console.error('WS Notif parse error', e) }
                    }
                });

                this.stompClient?.subscribe('/topic/users', (msg) => {
                    if (msg.body) {
                        try {
                            const users: OnlineUser[] = JSON.parse(msg.body);
                            authStore.setOnlineUsers(users);
                        } catch (e) { console.error('WS Users parse error', e) }
                    }
                });

                this.stompClient?.subscribe('/topic/cloud', (msg) => {
                    if (msg.body) {
                        try {
                            const stats = JSON.parse(msg.body);
                            useCloudStore().setStats(stats);
                        } catch (e) { console.error('WS Cloud parse error', e) }
                    }
                });
            };

            this.stompClient.onStompError = (frame) => {
                console.error('Broker reported error: ' + frame.headers['message']);
                ElNotification({
                    title: '❌ WebSocket错误',
                    message: frame.headers['message'] || '连接发生错误',
                    type: 'error',
                    duration: 5000
                });
            };

            this.stompClient.onDisconnect = () => {
                this.wsConnected = false;
                ElNotification({
                    title: '⚠️ 连接断开',
                    message: '与服务器的连接已断开，正在尝试重新连接...',
                    type: 'warning',
                    duration: 3000
                });
            };

            this.stompClient.activate();
        },
        disconnect() {
            if (this.stompClient) {
                this.stompClient.deactivate();
                this.wsConnected = false;
            }
        }
    }
})
