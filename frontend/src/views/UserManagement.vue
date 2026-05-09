<template>
  <div class="user-mgmt-container">
    <el-card class="cyber-card control-card" shadow="always">
      <template #header>
        <div class="card-header cyber-header">
          <span class="cyber-title">权限与身份管理中心 / IDENTITY CONTROL</span>
          <el-tag type="danger" effect="dark" size="small">ADMIN ONLY</el-tag>
        </div>
      </template>

      <el-table :data="users" class="cyber-table" style="width: 100%" v-loading="loading">
        <el-table-column label="终端 ID" prop="id" width="320" show-overflow-tooltip>
           <template #default="scope">
             <span class="mono-text">{{ scope.row.id }}</span>
           </template>
        </el-table-column>
        
        <el-table-column label="操作员名称" prop="username">
          <template #default="scope">
            <div class="user-cell">
              <span class="user-name">{{ scope.row.username }}</span>
              <el-tag :type="scope.row.role === 'ADMIN' ? 'danger' : 'success'" size="small" effect="plain" class="role-tag">
                {{ scope.row.role }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="最后接入时间" width="200">
          <template #default="scope">
            <span class="mono-text">{{ formatTime(scope.row.lastLoginTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="接入状态" width="120">
          <template #default="scope">
            <el-switch
              v-model="scope.row.enabled"
              active-color="#13ce66"
              inactive-color="#ff4949"
              @change="(val: boolean) => handleToggleStatus(scope.row, val)"
              :disabled="scope.row.username === 'admin'"
            />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button 
              type="danger" 
              size="small" 
              plain 
              @click="handleDelete(scope.row)"
              :disabled="scope.row.username === 'admin' || scope.row.username === appStore.user?.username"
            >
              吊销授权
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="audit-log mt-4">
       <el-alert
         title="安全准则：只有 ADMIN 角色的成员可以访问此终端。所有吊销操作将立即中断目标的实时网格接入，并清除其在边缘节点上的临时任务缓存。"
         type="info"
         :closable="false"
         show-icon
         class="cyber-alert"
       />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../services/api'
import { useAppStore } from '../store/appStore'
import { ElMessage, ElMessageBox } from 'element-plus'

const appStore = useAppStore()
const users = ref<any[]>([])
const loading = ref(false)

const fetchUsers = async () => {
    loading.value = true
    try {
        const res = await api.getUsers()
        users.value = res.data
    } catch (e) {
        ElMessage.error('无法获取用户列表，请检查权限')
    } finally {
        loading.value = false
    }
}

const handleToggleStatus = async (user: any, enabled: boolean) => {
    try {
        await api.updateUserStatus(user.id, enabled)
        ElMessage.success(`用户 ${user.username} 状态已更新`)
    } catch (e) {
        user.enabled = !enabled // Rollback
        ElMessage.error('状态更新失败')
    }
}

const handleDelete = (user: any) => {
    ElMessageBox.confirm(
        `确定要吊销操作员 [${user.username}] 的网格接入授权吗？此操作不可逆。`,
        '紧急授权吊销确认',
        {
            confirmButtonText: '确定吊销',
            cancelButtonText: '异常终止',
            type: 'warning',
            confirmButtonClass: 'el-button--danger'
        }
    ).then(async () => {
        try {
            await api.deleteUser(user.id)
            ElMessage.success('操作员授权已吊销')
            fetchUsers()
        } catch (e) {
            ElMessage.error('吊销失败')
        }
    })
}

const formatTime = (ts: number) => {
    if (!ts) return 'NEVER'
    const d = new Date(ts)
    return d.toLocaleString()
}

onMounted(fetchUsers)
</script>

<style scoped>
.user-mgmt-container {
    padding: 10px;
}

.user-cell {
    display: flex;
    align-items: center;
    gap: 8px;
}

.user-name {
    font-weight: 600;
    color: #c9d1d9;
}

.role-tag {
    font-family: var(--font-mono);
    font-size: 10px;
}

.mono-text {
    font-family: var(--font-mono);
    font-size: 12px;
    color: #8b949e;
}

.mt-4 {
    margin-top: 20px;
}

.cyber-alert {
    background: rgba(56, 139, 253, 0.1);
    border: 1px solid rgba(56, 139, 253, 0.3);
    color: #8b949e;
}

:deep(.cyber-table) {
    background: transparent !important;
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
    --el-table-header-bg-color: #161b22;
}

:deep(.el-table__row:hover > td) {
    background-color: rgba(0, 255, 204, 0.05) !important;
}
</style>
