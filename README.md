# DataSphere DolphinScheduler Migrator

独立的 DolphinScheduler 3.1.9 → DataSphere 迁移控制台。

## 当前能力

- 配置并测试 DolphinScheduler 3.1.9 元数据库连接
- 使用 DataSphere 账号密码登录并测试 REST API 连接
- 分析 Project / Workflow / Task / DAG / Schedule
- 识别不支持任务、数据源、资源文件、环境变量等迁移问题
- Dry Run，不修改 DataSphere
- 正式迁移目录、开发文件、Native Scheduler 工作流和 disabled 调度
- SQL / Shell / Python / SeaTunnel Task 迁为开发文件，DolphinScheduler Workflow 统一迁为真实 DataSphere Workflow，DAG 关系保留在工作流中
- DolphinScheduler Schedule 统一迁到 `/api/scheduler/workflows/{workflowId}/schedule`，不再写入旧 `dev_file_schedule`
- 实时查看进度、对象、问题和事件
- 支持取消任务、问题标记已处理、源→目标 ID 幂等映射

## 安全策略

源端仅通过 JDBC 读取；目标端只通过 DataSphere REST API 写入。正式迁移不会自动 Publish/Online，也不会启用 Native Scheduler Schedule。生产切换前必须先完成 Dry Run、问题清零和目标对象核对。
迁移工具自身数据库密码通过 `MIGRATOR_DB_PASSWORD` 环境变量注入，不写入代码仓库；DataSphere 登录密码仅保存在迁移工具状态库中且页面不回显。

## 生产迁移目标模型

```text
DolphinScheduler Project  -> DataSphere 一级目录
DolphinScheduler Task     -> DataSphere 开发文件
DolphinScheduler Workflow -> DataSphere Native Scheduler Workflow
DolphinScheduler DAG      -> Workflow Nodes / Edges
DolphinScheduler Schedule -> Native Scheduler Schedule (disabled)
```

迁移工具不再创建或更新 `dev_file_schedule`。代码内容继续由数据开发文件承载，生产调度统一由 Native Scheduler 的 `workflow_instance / task_instance` 体系产生运行实例。
