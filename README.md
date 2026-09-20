# DataSphere DolphinScheduler Migrator

独立的 DolphinScheduler 3.1.9 → DataSphere 迁移控制台。

## 当前能力

- 配置并测试 DolphinScheduler 3.1.9 元数据库连接
- 配置并测试 DataSphere REST API 连接
- 分析 Project / Workflow / Task / DAG / Schedule
- 识别不支持任务、数据源、资源文件、环境变量等迁移问题
- Dry Run，不修改 DataSphere
- 正式迁移目录、开发文件、工作流和 disabled 调度
- 实时查看进度、对象、问题和事件
- 支持取消任务、问题标记已处理、源→目标 ID 幂等映射

## 安全策略

源端仅通过 JDBC 读取；目标端通过 DataSphere REST API 写入。正式迁移不会自动 Publish/Online。
数据库密码通过 `MIGRATOR_DB_PASSWORD` 环境变量注入，不写入代码仓库。
