# 核心业务难题与破局真经 (Interview Highlights)

这份文档提炼了我们在开发**“分布式无人机边缘计算调度系统 (UAV-EDG-Scheduler)”**时遇到的最隐蔽、最经典的三个生产级 Bug。它们均涉及到高并发分布式锁、复杂状态机流转以及异步资源回收的深水区。非常适合在面试中展现您深入源码、精通调优的资深架构能力！

---

## 难题一：异步窃取导致的“幽灵内存泄漏” (Ghost Resource Leak)

### 🚨 案发现场 (Bug 表现)
在引入“重负载工作窃取 (Work-Stealing)”算法后，前端观测到一个极其灵异的现象：一台执行了窃取动作的无人机（UAV-3），它身上的任务明明已经执行降为 0，但其 CPU 核心负载指标却被永久锁死在 4/4（100% 满载），形成“僵尸节点”，并导致后续任务再也塞不进该节点。

### 🔍 深度溯源 (Root Cause)
这个 Bug 的本质是**异步回调与对象快照 (Closure Snapshot) 的绑定脱节**。
*   最初，任务被分配给源节点 (Node-1) 时，派发服务 `DispatchService` 开启了一个独立的 `CompletableFuture` 异步休眠线程来模拟其物理执行时长（例如 3 秒）。在这个闭包函数内，写死了任务完成后，向**最初被分配的节点 (Node-1) 发起释放 CPU 的指令**。
*   但在休眠的中间这 3 秒内，由于该任务在内存队列里“排队排得太久”，触发了高优先级的系统看门狗：被旁边的空闲节点 (Node-3) “窃取 (Steal)” 走了，其宿主 `AssignedUavId` 在 MySQL 数据库中被强行改写成了 UAV-3。
*   3 秒后，闭包线程苏醒，它依然拿着 3 秒前的旧内存快照，**跑去把 Node-1 的资源释放了！** 而真正干活的 Node-3 则永远收不到资源释放的信号，其状态由于只有增量没有减量，最终被死锁在 100%。

### 💡 破局方案 (Solution)
**延迟绑定与状态回源**。
彻底重构了 `DispatchService` 的资源释放逻辑。在异步线程休眠结束 `finally` 准备释放资源前，**强行增加一次针对数据库实时状态的二次校验 (Double Check)**：
```java
// Re-fetch to see if the task was stolen mid-execution by another node!
TaskInfo latestTask = taskRepository.findById(task.getId()).orElse(task);
String actualNodeId = latestTask.getAssignedUavId();
nodeService.release(actualNodeId, task.getRequiredCpu(), ...);
```
不仅根除僵尸泄漏，还发现了之前误伤 Node-1 导致 Node-1 会出现“负载量”的连锁 Bug。以此可向面试官展现你对并发编程对象生命周期的严谨控制能力。

---

## 难题二：系统保护倒置导致“削峰失效” (Fallback Short-circuiting)

### 🚨 案发现场 (Bug 表现)
我们原本设计边缘计算集群 (Edge Tier) 是为了扛住大并发。但只要压力稍大，或者发布一个需要消耗满载算力 (8 Cores) 的高阶任务，所有的流量都会直接绕过面前近在咫尺的 UAV 边缘节点，像泄洪一样全部击穿到最后的“Tier-3 云端服务器 (Cloud)”。边缘算力闲置率高达 95%。

### 🔍 深度溯源 (Root Cause)
由于混淆了**“任务排队准入 (Queue Admit)”**与**“物理内存强验证 (Physical Allocation)”**。
*   在 `NodeService.allocate()` 底层接口中，我们写死了一段防御性代码：`if (node.getAvailableCpu() >= requiredCpu)`。
*   一旦 UAV-2（理论最大算力 8C）哪怕已经在后台运行了一个 0.1C 的心跳任务，它的 `Available CPU` 就变成了 7.9。此时一个 8C 的任务压过来，底层分配器会直接暴力弹回 `false (资源不足)`。
*   调度层收到 `false` 后，认为前线彻底崩溃，触发了 Cloud Fallback。这导致节点永远无法接收超过 100% 阈值的任务，也就**永远无法形成等待队列**，更别提触发后置的 Work-Stealing 并发自适应逻辑了。

### 💡 破局方案 (Solution)
**解耦入队与分配，允许过载拥塞**。
我将 `allocate()` 接口的强校验拆除。只要调度层的总决策判断该任务**理论上没有超过单机的物理上限 (MaxCpu >= Task Required)**，就立刻强制塞入该物理机的待办队列，允许节点的**虚拟逻辑满载度 (Load) 飙升至 200% 或 300%**。
正是这种“有序的拥堵”，成功在边缘层积压了任务漏斗，彻底盘活了周围空闲无人机的“邻居算力支援/窃取协议”。让边缘层真正起到了为中心云削峰填谷的作用。

---

## 难题三：状态机死锁与不可逆流转 (RTH State Machine Lockup)

### 🚨 案发现场 (Bug 表现)
引入了电池衰减模拟后，无人机电量跌破 15% 会正常飞回充电基站 `(0,0)` 补给。然而当电量充满至 100% 后，系统日志明明打印了 "UAV-1 completed charging..."，但飞机就像被焊死在地上一样，再也不会飞出来继续巡发任务了。只有重启整个后端微服务才能解决。

### 🔍 深度溯源 (Root Cause)
在增加“前端鼠标拖拽无人机 (Drag-and-Drop) 自定义坐标”功能时，为了不与“后台自主寻路算法 (Waypoints巡航)”打架，引入了一个 `manualOverride` (人工接管) 的布尔锁。只要被鼠标拖过，就挂起自主巡航。
在 RTH 自动返航充能的生命周期流转中，系统仅恢复了 `Online = true`，但是漏掉了对其 `manualOverride` 挂起状态的解除。这就使得这架无人机在系统层面“已经可以接客了”，但在物理运动层面“引擎依然被软件锁死在怠速状态”。

### 💡 破局方案 (Solution)
**状态机闭环收口。**
严密梳理了节点状态机的流转枚举，将 `Online`、`Charging`、`RTHMode` 和 `ManualOverride` 四个状态绑定到一处集中的状态机出口函数。当电量满血满足跳出条件时，必须执行完整的上下文清理：
```java
node.setRthMode(false);
node.setCharging(false);
node.setOnline(true);
node.setManualOverride(false); // <--- Clear sticky flags to release routing engine lock
```
展现了对于状态机复杂联动、UI 与 后台隔离、兜底状态重置的完善工程思维。

---

## 难题四：Lombok 布尔映射与 Jackson 反序列化黑洞 (Boolean Mismatch)

### 🚨 案发现场 (Bug 表现)
前后端通过 WebSocket 建立连接后，一旦后端向前端推送无人机的在线状态 `is_online`，整个系统就会发生反序列化崩溃，或者前端拿到的在线状态永远是 `undefined`。

### 🔍 深度溯源 (Root Cause)
这是 Java 的一处极度经典的框架协作坑。实体类中定义了带有 `is` 前缀的布尔变量 `private boolean isOnline;`，在被 Lombok 的 `@Data` 生成 getter 时，其方法名会被默认为 `isOnline()` 而非 `getIsOnline()`。
而网络传输层的 Jackson JSON 序列化器在通过反射转换对象时，遇到 `isOnline()` 方法，会按照 Bean 规范自动剥除 `is` 前缀，将下发的 JSON 键名默默篡改为 `{"online": true}`。这就导致了前后端的数据字典产生了无法调和的字段名错位（前端苦苦等待 `isOnline`，而后端发的是 `online`）。

### 💡 破局方案 (Solution)
**DTO 字段级强制定向。**
在实体类的关键布尔属性上，强制打上 `@JsonProperty("isOnline")` 注解，彻底锁死序列化到 JSON 时的 Key 名称，直接绕开 Lombok 和 Jackson 的默认行为冲突。这展现了开发者对底层常见中间件源码黑盒映射规则的深刻理解。

---

## 难题五：SVG 视图裁切与原生零点盲区 (SVG ViewBox Clipping)

### 🚨 案发现场 (Bug 表现)
在前端的“态势感知雷达”上，我们精准通过 SVG 画出了一个位于 `(0,0)` 坐标的充电基站。无人机到了电量耗尽时，确实也朝着坐标零点飞去了，但是在 UI 界面上，那个庞大的充电补给站却不可思议地“隐形”了，飞机如同在虚空中凭空充电。

### 🔍 深度溯源 (Root Cause)
雷达地图默认的 SVG 视口 `viewBox` 被设定为 `0 0 100 100`。
这意味着 `x:0, y:0` 是被死死限制在整个画板的“绝对左上角第一颗像素边缘”。当我们以 `(0,0)` 为圆心绘制一个半径为 10 的圆形基站时，由于没有任何内部填充余量，这个圆有四分之三的面积直接被屏幕死区的边界无情地物理裁切 (Clipping Rectangle) 掉了，甚至残存的边缘也与边框重合，导致在视觉上完全丢失。

### 💡 破局方案 (Solution)
**坐标系广角扩展与视口漂移 (Viewport Shifting)。**
将前端 SVG 的 `viewBox` 放大并偏移为 `-20 -20 140 140`。这一步“镜头后拉”操作，使得绝对坐标系零点 `(0,0)` 在不改变任何后台真实业务几何逻辑的前提下，被推演到了视觉画面的偏内部，使得充能基站完美显形。展现了极强的像素级前端渲染调试与向量几何处理能力。

---

## 难题六：JPA 生命碰撞与异步游离态幻数 (Hibernate Detached Entity Race)

### 🚨 案发现场 (Bug 表现)
系统在高并发压测时，瀑布流审计报告 (TraceLog) 出现了严重的幽灵数据：大量明明已经正常派发的任务，却没有对应的派发耗时记录，或者凭空多出了几条没有外键绑定的孤儿日志。

### 🔍 深度溯源 (Root Cause)
由于为了不阻塞主干派发流程，大量对 `TaskTraceLog` 的 MySQL 持久化操作被投入了 `CompletableFuture.runAsync` 中异步更新。
但 Spring Boot 中默认的 Hibernate JPA EntityManager 是**线程局部 (ThreadLocal) 隔离的**。
主线程处理完业务返回 HTTP 响应时，立刻提交并关闭了该线程的 Session。而瞬间苏醒的异步闭包线程试图拿着刚才主线程传过来的持久化映射对象去执行 `.save()` 将延迟填入时，Hibernate 检测到这个对象的会话已经消亡，判定其为“游离态对象 (Detached Entity)”。底层机制不仅没有执行 `UPDATE`，反而在尝试级联合并时引发脏写，错误地触发了 `INSERT` 生成了新行。

### 💡 破局方案 (Solution)
**异步线程内无状态化与状态回源 (Re-Fetching in Async Scope)。**
禁止在跨线程的异步闭包中裸传 JPA 实体对象 (Entity)。在异步代码块中，仅传入无状态的只读数据与主键 ID（如 `String taskId`），并在异步线程内部，**重新调用 `repository.findByTaskId(id)` 单独发起一次查询**，以获取从属于当前新异步线程独占 Session 绑定态的 Entity 对象然后再做更新。这凸显了对 ORM 框架第一缓存生命周期及高并发事务一致性的深厚功底。
