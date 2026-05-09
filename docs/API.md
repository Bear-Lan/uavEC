# API 接口文档

## 基础信息

- **Base URL**: `http://localhost:8080/api`
- **认证方式**: Token 认证 (X-Auth-Token Header)
- **Content-Type**: `application/json`

---

## 1. 认证接口 (Auth)

### 1.1 用户注册
```
POST /api/auth/register
```
**请求体**:
```json
{
  "username": "string",
  "password": "string",
  "x": 50,
  "y": 50
}
```
**响应**: `200 OK`

---

### 1.2 用户登录
```
POST /api/auth/login
```
**请求体**:
```json
{
  "username": "string",
  "password": "string"
}
```
**响应**: `200 OK`

---

### 1.3 获取当前用户信息
```
GET /api/auth/me?username={username}
```
**响应**: `200 OK`

---

### 1.4 更新个人资料
```
PUT /api/auth/profile
```
**请求体**:
```json
{
  "currentUsername": "string",
  "newUsername": "string",
  "x": 50,
  "y": 50
}
```
**响应**: `200 OK`

---

## 2. 用户管理 (Admin)

### 2.1 获取所有用户
```
GET /api/users
```
**响应**: `200 OK`

---

### 2.2 更新用户状态
```
PUT /api/users/{id}/status?enabled={true|false}
```
**响应**: `200 OK`

---

### 2.3 删除用户
```
DELETE /api/users/{id}
```
**响应**: `200 OK`

---

## 3. 节点管理

### 3.1 获取所有节点
```
GET /api/nodes
```
**响应**: `200 OK`

---

### 3.2 添加新节点
```
POST /api/nodes/add
```
**响应**: `200 OK`

---

### 3.3 删除节点
```
DELETE /api/nodes/{id}
```
**响应**: `200 OK`

---

### 3.4 设置节点状态（在线/离线）
```
POST /api/nodes/{id}/status?online={true|false}
```
**响应**: `200 OK`

---

### 3.5 设置节点位置
```
POST /api/nodes/{id}/position?x={x}&y={y}
```
**响应**: `200 OK`

---

### 3.6 紧急充电
```
POST /api/nodes/{id}/charge
```
**响应**: `200 OK`

---

### 3.7 创建快照
```
POST /api/nodes/snapshot
```
**响应**: `200 OK`

---

### 3.8 回滚快照
```
POST /api/nodes/rollback
```
**响应**: `200 OK`

---

### 3.9 更新节点配置
```
PUT /api/nodes/{id}/config
```
**请求体**:
```json
{
  "maxCpu": 16,
  "maxMemory": 32768,
  "networkBandwidth": 1000
}
```
**响应**: `200 OK`

---

## 4. 任务管理

### 4.1 提交单个任务
```
POST /api/tasks
```
**请求体**:
```json
{
  "type": "IMAGE_PROCESSING",
  "dataSize": 50,
  "requiredCpu": 2,
  "requiredMemory": 512,
  "priority": 3,
  "originX": 50,
  "originY": 50,
  "offloadAlgorithm": "latency",
  "customW1": 0.5,
  "customW2": 0.5,
  "customW3": 0.5
}
```
**响应**: `200 OK`

---

### 4.2 批量提交任务
```
POST /api/tasks/batch
```
**请求体**:
```json
[
  { "type": "IMAGE_PROCESSING", "dataSize": 50, ... },
  { "type": "AI_INFERENCE", "dataSize": 100, ... }
]
```
**响应**: `200 OK`

---

### 4.3 获取任务追踪日志
```
GET /api/tasks/{taskId}/trace
```
**响应**: `200 OK`

---

## 5. 流量生成

### 5.1 启动流量生成器
```
POST /api/traffic/start
```
**请求体**:
```json
{
  "lambda": 5.0,
  "algorithm": "greedy",
  "originX": 50,
  "originY": 50,
  "customW1": 0.5,
  "customW2": 0.5,
  "customW3": 0.5
}
```
**响应**: `200 OK`

---

### 5.2 停止流量生成器
```
POST /api/traffic/stop
```
**响应**: `200 OK`

---

### 5.3 获取流量状态
```
GET /api/traffic/status
```
**响应**: `200 OK`
```json
{
  "active": true,
  "lambda": 5.0
}
```

---

## 6. 指标统计

### 6.1 获取批次指标
```
GET /api/metrics/{batchId}
```
**响应**: `200 OK`

---

### 6.2 获取指标历史
```
GET /api/metrics/history
```
**响应**: `200 OK`

---

### 6.3 导出 CSV
```
GET /api/metrics/export?batchId={batchId}
```
**响应**: `200 OK` (CSV 文件)

---

## 7. 系统状态

### 7.1 获取系统状态
```
GET /api/system/status
```
**响应**: `200 OK`

---

## 错误响应格式

```json
{
  "error": "错误信息",
  "message": "详细描述"
}
```

---

## 任务类型 (TaskType)

| 类型 | 描述 |
|------|------|
| `IMAGE_PROCESSING` | 图像处理 |
| `AI_INFERENCE` | AI 推理 |
| `DATA_STREAM` | 数据流处理 |

---

## 调度算法

| 算法 | 描述 |
|------|------|
| `greedy` | 贪心算法 - 选择 CPU 剩余最多的节点 |
| `wfq` | 加权公平队列 - 选择任务数最少的节点 |
| `geo` | 地理拓扑 - 基于距离、电量、CPU 的加权选择 |
| `custom` | 自定义算法 - 用户可配置权重 |