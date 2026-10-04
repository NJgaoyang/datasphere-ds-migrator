# DataForge DolphinScheduler Migrator

独立的 DolphinScheduler 3.1.9 → DataForge 迁移控制台。

## 当前能力

- 配置并测试 DolphinScheduler 3.1.9 元数据库连接
- 使用 DataForge 账号密码登录，通过 `platform_session` Cookie 调用当前 DataForge REST API
- 分析 Project / Workflow / Task / DAG / Schedule
- 识别不支持任务、数据源、资源文件、环境变量等迁移问题
- Dry Run，不修改 DataForge
- 支持“一键迁移全部”：无需先手工执行分析，直接迁移当前全部可支持对象
- 正式迁移目录、开发文件、Native Scheduler 工作流和 disabled 调度
- SQL / Shell / Python / SeaTunnel Task 迁为开发文件，DolphinScheduler Workflow 统一迁为真实 DataForge Workflow，DAG 关系保留在工作流中
- DolphinScheduler Schedule 统一迁到 `/api/scheduler/workflows/{workflowId}/schedule`，不再写入旧 `dev_file_schedule`
- 同一 Workflow 内依赖只迁为 DAG Edge；安全的根 `DEPENDENT` 节点迁为 disabled Native Scheduler 跨工作流依赖，不再生成“依赖 SQL”或第二份调度
- 实时查看进度、对象、问题和事件
- 支持取消任务、问题标记已处理、源→目标 ID 幂等映射
- 支持“一键清除全部迁移数据”：按 工作流依赖 → 工作流下线/删除 → 开发任务下线 → 回收箱彻底删除 的顺序清理由本工具创建并记录在 `migration_object_map` 中的目标对象

## 一键迁移

调用 `POST /api/runs/migrate-all`，迁移当前 DolphinScheduler 3.1.9 中的全部工作流范围。目标工作流保持 Draft/Offline，Native Scheduler Schedule 保持 disabled，不自动切生产。

需要精细控制范围时，仍可使用原有流程：分析 → Dry Run → 选择工作流 → 正式迁移。

## 一键清除

调用 `POST /api/cleanup/all`，只清理由本迁移工具记录的目标对象，不扫描或删除 DataForge 中用户手工创建的对象。

清除顺序固定为：

```text
跨工作流依赖
  -> DataForge Workflow（已发布时先下线）
  -> DataForge 开发任务（已发布时先下线）
  -> 开发任务回收箱彻底删除
```

如果对象已在 DataForge 外部被删除，迁移工具会清理对应的陈旧映射；如果对象仍被未清除的其他 DataForge 对象引用，则保留映射并返回失败明细。

## 安全策略

源端仅通过 JDBC 读取；目标端只通过 DataForge REST API 写入。正式迁移不会自动 Publish/Online，也不会启用 Native Scheduler Schedule。生产切换前建议先完成 Dry Run、问题清零和目标对象核对。
迁移工具自身数据库密码通过 `MIGRATOR_DB_PASSWORD` 环境变量注入，不写入代码仓库；DataForge 登录密码仅保存在迁移工具状态库中且页面不回显。

“一键清除”是破坏性操作，因此只清理由本工具建立映射的迁移目标，不删除项目、数据源、用户、系统配置，也不会扫描并删除用户在 DataForge 中手工创建但未被本工具记录的任务。

## 生产迁移目标模型

```text
DolphinScheduler Project  -> DataForge 一级目录
DolphinScheduler Task     -> DataForge 开发文件
DolphinScheduler Workflow -> DataForge Native Scheduler Workflow
DolphinScheduler DAG      -> Workflow Nodes / Edges
DolphinScheduler Schedule -> Native Scheduler Schedule (disabled)
```

迁移工具不再创建或更新 `dev_file_schedule`。代码内容继续由数据开发文件承载，生产调度统一由 Native Scheduler 的 `workflow_instance / task_instance` 体系产生运行实例。
