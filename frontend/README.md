# UAV Edge Computing Task Scheduler - Frontend

无人机边缘计算任务调度系统前端，基于 Vue 3 + TypeScript + Vite 构建。

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3.5 + TypeScript 5.9 |
| 构建工具 | Vite 7.3 |
| UI 组件库 | Element Plus 2.13 |
| 状态管理 | Pinia 3.0 |
| 路由 | Vue Router 5.0 |
| 图表 | ECharts 6.0 |
| HTTP 客户端 | Axios |
| WebSocket | STOMP.js + SockJS |

## 项目结构

```
frontend/
├── src/
│   ├── assets/          # 静态资源
│   ├── components/       # 公共组件
│   ├── layout/          # 布局组件
│   ├── router/          # 路由配置
│   ├── services/        # API 服务
│   ├── store/           # Pinia 状态管理
│   │   ├── appStore.ts  # 应用状态
│   │   ├── authStore.ts # 认证状态
│   │   ├── nodeStore.ts # 节点状态
│   │   ├── taskStore.ts # 任务状态
│   │   └── wsStore.ts   # WebSocket 状态
│   ├── views/           # 页面视图
│   │   ├── Analytics.vue  # 数据分析
│   │   ├── Cluster.vue     # 集群管理
│   │   ├── Dashboard.vue   # 仪表盘
│   │   ├── Login.vue       # 登录
│   │   ├── Trace.vue       # 任务追踪
│   │   └── UserManagement.vue # 用户管理
│   ├── App.vue
│   └── main.ts
├── package.json
└── vite.config.ts
```

## 功能模块

### Dashboard - 系统总览仪表盘
- 雷达视图可视化节点拓扑
- 实时任务状态展示
- 系统运行状态概览

### Cluster - UAV 节点集群管理
- 动态添加/移除无人机节点
- 节点状态监控（在线/离线/充电中）
- 节点算力配置（CPU/内存/带宽）
- 电池电量与 RTH 返航模拟
- 快照创建与回滚

### Trace - 任务执行轨迹追踪
- 任务全生命周期耗时追踪
- 排队延迟、传输延迟、计算延迟可视化
- 任务状态流转展示

### Analytics - 调度数据分析与可视化
- ECharts 多维指标图表
- 延迟/能耗/带宽趋势分析
- 批次指标历史查询

### User Management - 用户权限管理
- 用户注册与登录认证
- 角色权限管理（ADMIN/USER）

## 卸载策略支持

系统支持多种任务卸载算法，可在发布任务时选择：

| 算法 | 描述 | 优化目标 |
|------|------|----------|
| `greedy` | 贪心算法 - 选择 CPU 剩余最多的节点 | 最大算力利用 |
| `wfq` | 加权公平队列 - 选择任务数最少的节点 | 负载均衡 |
| `geo` | 地理拓扑 - 基于距离、电量、CPU 加权 | 低延迟 |
| `custom` | 自定义算法 - 用户可配置权重 | 自定义调度 |
| `latency` | 延迟最优 - 基于 M/M/1 排队论 | 最小化延迟 |
| `energy` | 能耗最优 - 基于 DVFS 动态调频 | 最小化能耗 |
| `adaptive` | 自适应部分卸载 | 边缘/云端平衡 |
| `dqn` | 深度强化学习 - DQN 智能决策 | 长期累积奖励 |

## 快速开始

### 安装依赖

```bash
npm install
```

### 开发环境启动

```bash
npm run dev
```

### 构建生产版本

```bash
npm run build
```

### 预览生产构建

```bash
npm run preview
```

## WebSocket 实时通信

前端通过 STOMP.js + SockJS 与后端建立 WebSocket 连接，实现毫秒级状态推送：

```typescript
// 状态订阅示例
stompClient.subscribe('/topic/system/status', (message) => {
  const status = JSON.parse(message.body);
  // 更新 Pinia store
});
```

## API 服务

前端通过 Axios 封装后端 REST API：

| 服务 | 说明 |
|------|------|
| `/api/auth/*` | 认证接口 |
| `/api/nodes/*` | 节点管理接口 |
| `/api/tasks/*` | 任务管理接口 |
| `/api/traffic/*` | 流量生成接口 |
| `/api/metrics/*` | 指标查询接口 |

## 后端服务

后端基于 Spring Boot 3.1.5 + Java 17 构建，提供：
- REST API 接口 (`/api/*`)
- WebSocket 实时推送 (`/ws`)
- MySQL 持久化存储
- Redis 分布式队列与锁

详见 [backend/README.md](../backend/README.md) 和 [docs/architecture.md](../docs/architecture.md)