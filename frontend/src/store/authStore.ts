import { defineStore } from 'pinia'
import { api } from '../services/api'

export interface User {
    id: string
    username: string
    role: string
    token: string
    x: number
    y: number
}

export interface OnlineUser {
    username: string
    x: number
    y: number
    loginTime: number
}

export const useAuthStore = defineStore('auth', {
    state: () => ({
        user: null as User | null,
        onlineUsers: [] as OnlineUser[],
    }),
    actions: {
        async login(username: string, password?: string) {
            const res = await api.login(username, password)
            const { user: u, token } = res.data
            this.user = { id: u.id, username: u.username, role: u.role, token, x: u.x, y: u.y }
            sessionStorage.setItem('edg:username', username)
            sessionStorage.setItem('edg:token', token)
        },
        async register(username: string, password?: string, x?: number, y?: number) {
            const res = await api.register(username, password, x, y)
            const u = res.data
            this.user = { id: u.id, username: u.username, role: u.role, token: '', x: u.x, y: u.y }
            sessionStorage.setItem('edg:username', username)
        },
        logout() {
            if (this.user) {
                api.logout(this.user.username).catch(() => {})
            }
            this.user = null
            sessionStorage.removeItem('edg:username')
            sessionStorage.removeItem('edg:token')
        },
        async restoreSession(): Promise<boolean> {
            const username = sessionStorage.getItem('edg:username')
            const token = sessionStorage.getItem('edg:token')
            if (!username || !token) return false
            try {
                const res = await api.getMe(username)
                const u = res.data
                this.user = { id: u.id, username: u.username, role: u.role, token, x: u.x, y: u.y }
                // 通知后端用户已上线（用于多用户在线显示）
                api.announceOnline(username).catch(() => {})
                return true
            } catch {
                this.logout()
                return false
            }
        },
        async updateUser(newUsername: string, x: number, y: number) {
            if (!this.user) return false
            const res = await api.updateProfile(this.user.username, newUsername, x, y)
            const u = res.data
            this.user = { id: u.id, username: u.username, role: u.role, token: this.user.token, x: u.x, y: u.y }
            sessionStorage.setItem('edg:username', u.username)
            return true
        },
        setOnlineUsers(users: OnlineUser[]) {
            this.onlineUsers = users
        }
    }
})
