import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../views/Layout.vue'
import Dashboard from '../views/Dashboard.vue'
import Cluster from '../views/Cluster.vue'
import Analytics from '../views/Analytics.vue'
import Trace from '../views/Trace.vue'

import Login from '../views/Login.vue'
import { useAppStore } from '../store/appStore'

const router = createRouter({
    history: createWebHistory(),
    routes: [
        {
            path: '/login',
            name: 'Login',
            component: Login
        },
        {
            path: '/',
            component: Layout,
            beforeEnter: async (_to, _from) => {
                const appStore = useAppStore();
                if (appStore.user) {
                    return true;
                } else {
                    const restored = await appStore.restoreSession();
                    if (restored) {
                        return true;
                    } else {
                        return '/login';
                    }
                }
            },
            children: [
                {
                    path: '',
                    name: 'Dashboard',
                    component: Dashboard
                },
                {
                    path: 'cluster',
                    name: 'Cluster',
                    component: Cluster
                },
                {
                    path: 'analytics',
                    name: 'Analytics',
                    component: Analytics
                },
                {
                    path: 'trace',
                    name: 'Trace',
                    component: Trace
                },
                {
                    path: 'users',
                    name: 'UserManagement',
                    component: () => import('../views/UserManagement.vue'),
                    meta: { requiresAdmin: true }
                }
            ]
        }
    ]
})

router.beforeEach((to, _from) => {
    const appStore = useAppStore()
    if (to.meta.requiresAdmin && appStore.user?.role !== 'ADMIN') {
        return '/'
    }
})

export default router
