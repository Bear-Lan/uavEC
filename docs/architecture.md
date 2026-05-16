# 系统架构文档

## 1. 系统概述

无人机边缘计算调度与监控系统 (UAV Edge Computing System) 是一个分布式雾计算弹性调度测试平台，实现了地面数据源、无人机边缘信关站与云端服务器之间的智能算力卸载。

---

## 2. 系统架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              前端 (Vue 3 + Vite)                         │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────┐   │
│  │Dashboard│  │ Cluster │  │Analytics│  │  Trace  │  │UserManagement│  │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘  └──────┬──────┘   │
│       │            │            │            │               │          │
│       └────────────┴────────────┴────────────┴───────────────┘          │
│                                    │                                     │
│                           ┌────────▼────────┐                           │
│                           │   Vue Router    │                           │
│                           │   Pinia Store   │                           │
│                           └────────┬────────┘                           │
└────────────────────────────────────┼────────────────────────────────────┘
                                     │ HTTP / WebSocket
┌────────────────────────────────────┼────────────────────────────────────┐
│                            Spring Boot 后端                              │
│                                    │                                     │
│  ┌─────────────────────────────────▼─────────────────────────────────┐  │
│  │                        REST API Controller                        │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐         │  │
│  │  │   Auth   │  │   App    │  │  Config  │  │  User    │         │  │
│  │  │Controller│  │Controller│  │Controller│  │Controller│         │  │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘         │  │
│  └───────┼─────────────┼─────────────┼─────────────┼───────────────┘  │
│          │             │             │             │                   │
│  ┌───────▼─────────────▼─────────────▼─────────────▼───────────────┐  │
│  │                         Service Layer                             │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌───────────┐  │  │
│  │  │Dispatch    │  │  Task      │  │  Node      │  │TrafficGen │  │  │
│  │  │Service     │  │  Service   │  │  Service   │  │ Service   │  │  │
│  │  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬─────┘  │  │
│  │        │               │               │               │        │  │
│  │  ┌─────▼───────────────▼───────────────▼───────────────▼─────┐  │  │
│  │  │         Offloading Strategy (卸载策略模式)                 │  │  │
│  │  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐    │  │  │
│  │  │  │ Latency │  │ Energy  │  │Adaptive │  │   DQN   │    │  │  │
│  │  │  │Optimal  │  │Optimal  │  │ Partial │  │         │    │  │  │
│  │  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘    │  │  │
│  │  └───────────────────────────────────────────────────────────┘  │  │
│  │  ┌───────────────────────────────────────────────────────────┐  │  │
│  │  │              CloudSimulationService (云端模拟)             │  │  │
│  │  └───────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                     │                                     │
└─────────────────────────────────────┼────────────────────────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
      ┌───────▼───────┐      ┌────────▼────────┐    ┌────────▼────────┐
      │     MySQL     │      │      Redis      │    │    WebSocket    │
      │   (持久化)    │      │  (分布式队列/锁) │    │   (实时推送)    │
      └───────────────┘      └─────────────────┘    └─────────────────┘
```

---

## 3. 技术栈

| 层级 | 技术 | 用途 |
|------|------|------|
| 前端框架 | Vue 3 + TypeScript | UI 开发 |
| 前端构建 | Vite | 开发服务器与打包 |
| UI 组件 | Element Plus | 界面组件库 |
| 状态管理 | Pinia | 前端状态管理 |
| 图表库 | ECharts | 数据可视化 |
| 通信 | STOMP + SockJS | WebSocket 实时通信 |
| 后端框架 | Spring Boot 3.1.5 | REST API 开发 |
| Java 版本 | Java 17 | 运行环境 |
| 数据库 | MySQL 8.0 | 持久化存储 |
| 缓存/队列 | Redis + Redisson | 分布式锁、任务队列 |
| 消息推送 | Spring WebSocket | 实时状态推送 |

---

## 4. 核心模块

### 4.1 调度服务 (DispatchService)

**职责**:
- 任务分发决策
- 调度算法选择（greedy/wfq/geo/custom/latency/energy/adaptive/dqn）
- 算力卸载决策（边缘/云端/部分卸载）
- Shannon 带宽模型计算
- 云端降级处理

**关键方法**:
```java
public DispatchResult dispatch(TaskInfo task)
private boolean decideOffload(TaskInfo task, UAVNode node)
private DispatchResult executeCloudFallback(TaskInfo task, String reason)
```

### 4.2 任务服务 (TaskService)

**职责**:
- 任务队列管理（Redisson Deque）
- 优先级队列处理
- 工作窃取算法
- 任务迁移

**关键方法**:
```java
public String submitTask(TaskInfo task)
public void processTaskQueue()
private void workStealing()
```

### 4.3 节点服务 (NodeService)

**职责**:
- 节点生命周期管理
- 电池模型模拟
- RTH (Return-To-Home) 返航
- 快照与回滚
- 分布式锁保护

**关键方法**:
```java
public UAVNode addNode()
public boolean deleteNode(String nodeId)
public void setNodeStatus(String nodeId, boolean online)
public void emergencyCharge(String nodeId)
public void createSnapshot()
public void restoreSnapshot()
```

### 4.4 卸载策略 (Offloading Strategy)

采用策略模式，支持多种卸载算法：

| 算法 | 策略 | 适用场景 |
|------|------|---------|
| **LatencyOptimal** | 基于 M/M/1 排队论的延迟最优 | 追求低延迟 |
| **EnergyOptimal** | 基于 DVFS 的能耗最优 | 追求节能 |
| **AdaptivePartial** | 自适应部分卸载 | 边缘+云端混合 |
| **DQN** | 深度强化学习 | 智能决策 |

**核心接口**:
```java
public interface OffloadingStrategy {
    OffloadResult calculateOffloadingPath(UAVNode node, TaskInfo task, CloudStatus cloud);
    String getName();
}
```

**卸载结果 (OffloadResult)**:
```java
public enum Decision {
    EDGE,      // 边缘执行（无人机）
    CLOUD,     // 云端执行
    PARTIAL    // 部分卸载（边缘+云端）
}
```

### 4.5 云端模拟服务 (CloudSimulationService)

**职责**:
- 模拟云端服务器状态
- 延迟和带宽建模
- 边缘故障时提供降级能力

---

---

## 5. 数据流

### 5.1 任务提交流程

```
用户提交任务
    ↓
TaskService.submitTask()
    ↓
存入 Redisson 优先级队列
    ↓
定时任务 processTaskQueue() 消费
    ↓
DispatchService.dispatch() 调度
    ↓
选择最优节点 → 本地执行 / 边缘卸载 / 云端降级
    ↓
WebSocket 推送状态更新
```

### 5.2 实时状态推送

```
后端状态变更
    ↓
SimpMessagingTemplate.convertAndSend()
    ↓
WebSocket (STOMP)
    ↓
前端 wsStore 接收
    ↓
Pinia 状态更新 → 界面响应式更新
```

---

## 6. 数据库设计

### 6.1 核心实体

| 实体 | 表名 | 描述 |
|------|------|------|
| TaskInfo | tasks | 任务信息 |
| UAVNode | uav_nodes | 无人机节点 |
| Operator | operators | 用户/操作员 |
| TaskTraceLog | task_trace_logs | 任务追踪日志 |
| BatchMetricsSummary | batch_metrics | 批次指标汇总 |

### 6.2 关系图

```
Operator (1) ─────< (N) TaskInfo
UAVNode (1) ─────< (N) TaskInfo
TaskInfo (1) ─────< (N) TaskTraceLog
```

---

## 7. 安全机制

### 7.1 认证

- BCrypt 密码加密存储
- Token-based 认证 (Session)
- X-Auth-Token Header 验证

### 7.2 权限

- ADMIN / USER 角色分离
- 路由守卫控制

### 7.3 分布式锁

- Redisson 分布式锁保护临界资源
- 节点操作、任务分配互斥

---

## 8. 部署架构

### 8.1 开发环境

```
┌─────────────┐     ┌─────────────┐
│  Frontend   │     │   Backend   │
│  :5173      │────▶│   :8080     │
└─────────────┘     └──────┬──────┘
                          │
         ┌────────────────┼────────────────┐
         │                │                │
    ┌────▼────┐     ┌─────▼────┐    ┌────▼────┐
    │  MySQL  │     │  Redis   │    │ WebSocket
    │  :3306  │     │  :6379   │    │ (内置)
    └─────────┘     └──────────┘    └─────────┘
```

### 8.2 生产环境（可选 Docker）

```
┌─────────────────────────────────────────┐
│              docker-compose              │
│  ┌──────────┐  ┌──────────┐             │
│  │ frontend │  │ backend  │             │
│  │ (nginx)  │  │(spring)  │             │
│  └────┬─────┘  └────┬─────┘             │
│       │             │                   │
│  ┌────▼─────────────▼────┐              │
│  │     internal network   │              │
│  └──────────┬─────────────┘              │
│             │                            │
│  ┌──────────┼──────────┐                 │
│  │   MySQL  │  Redis   │                 │
│  └──────────┴──────────┘                 │
└─────────────────────────────────────────┘
```

---

## 9. 关键特性

1. **智能算力卸载** - 边缘/云端/部分卸载三级降级（无LOCAL概念）
2. **多卸载策略** - LatencyOptimal/EnergyOptimal/AdaptivePartial/DQN
3. **分布式锁** - Redisson 保证高并发一致性
4. **实时监控** - WebSocket 毫秒级推送
5. **故障恢复** - 工作窃取、任务迁移、快照回滚
6. **云端模拟** - CloudSimulationService 提供云端降级能力