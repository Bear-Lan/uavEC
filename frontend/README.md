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

- **Dashboard** - 系统总览仪表盘
- **Cluster** - UAV 节点集群管理
- **Trace** - 任务执行轨迹追踪
- **Analytics** - 调度数据分析与可视化
- **User Management** - 用户权限管理
- **登录认证** - 基于 JWT 的用户认证

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

## 后端服务

后端基于 Spring Boot 3.1.5 + Java 17 构建，提供 REST API 和 WebSocket 通信接口。

详见 [backend/README.md](../backend/README.md)

## 调度算法

系统支持多种任务调度算法：

- **Greedy Algorithm** - 贪心算法
- **WFQ (Weighted Fair Queuing)** - 加权公平队列
- **Geo Algorithm** - 地理位置调度
- **Custom Algorithm** - 自定义调度策略
