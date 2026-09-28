<template>
  <section class="panel lineage-panel">
    <div class="lineage-heading">
      <div>
        <h2>SQL 依赖分析</h2>
        <p>读取迁移后的 DataSphere SQL，抽取输入表 / 输出表，并推导前置依赖。分析不会修改任何任务；只有点击应用才会写入。</p>
      </div>
      <el-button type="primary" :loading="loading" @click="scan">扫描 SQL 依赖</el-button>
    </div>

    <div class="metric-grid" v-if="result">
      <div><span>SQL 任务</span><strong>{{ result.taskCount || 0 }}</strong></div>
      <div><span>推导关系</span><strong>{{ result.relationCount || 0 }}</strong></div>
      <div><span>可应用</span><strong>{{ result.readyCount || 0 }}</strong></div>
      <div><span>已存在</span><strong>{{ result.existingCount || 0 }}</strong></div>
      <div><span>冲突/阻塞</span><strong>{{ result.conflictCount || 0 }}</strong></div>
    </div>

    <el-alert v-if="result" type="info" :closable="false" show-icon
      title="同工作流依赖写入 Workflow DAG；跨工作流依赖写入 disabled Native Scheduler 工作流依赖。不会新增 SQL 节点，也不会新增第二份 Cron。" />

    <div v-if="result" class="apply-bar">
      <el-checkbox v-model="applyOptions.sameWorkflow">同工作流前置依赖</el-checkbox>
      <el-checkbox v-model="applyOptions.crossWorkflow">跨工作流前置依赖</el-checkbox>
      <span class="spacer" />
      <el-button type="danger" plain :loading="applying" :disabled="!result.readyCount || !hasApplySelection" @click="applyDependencies">
        一键应用可用依赖
      </el-button>
    </div>

    <el-tabs v-if="result" v-model="tab" class="result-tabs">
      <el-tab-pane :label="`依赖关系 ${result.relations?.length || 0}`" name="relations">
        <el-table :data="result.relations || []" height="500">
          <el-table-column prop="tableName" label="依赖表" min-width="180" show-overflow-tooltip />
          <el-table-column label="前置任务" min-width="180" show-overflow-tooltip>
            <template #default="s">{{ s.row.producerTaskName }}</template>
          </el-table-column>
          <el-table-column label="当前任务" min-width="180" show-overflow-tooltip>
            <template #default="s">{{ s.row.consumerTaskName }}</template>
          </el-table-column>
          <el-table-column label="范围" width="130">
            <template #default="s">
              <el-tag size="small" :type="s.row.scope === 'SAME_WORKFLOW' ? 'info' : 'warning'">
                {{ s.row.scope === 'SAME_WORKFLOW' ? '同工作流' : '跨工作流' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="s"><el-tag size="small" :type="statusType(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="message" label="说明" min-width="260" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`SQL 表血缘 ${result.tasks?.length || 0}`" name="tasks">
        <el-table :data="result.tasks || []" height="500">
          <el-table-column prop="taskName" label="任务" min-width="180" show-overflow-tooltip />
          <el-table-column prop="sourceWorkflowName" label="工作流" min-width="170" show-overflow-tooltip />
          <el-table-column label="输入表" min-width="260">
            <template #default="s"><span class="table-list">{{ (s.row.inputTables || []).join('、') || '-' }}</span></template>
          </el-table-column>
          <el-table-column label="输出表" min-width="260">
            <template #default="s"><span class="table-list">{{ (s.row.outputTables || []).join('、') || '-' }}</span></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`外部输入表 ${result.externalTables?.length || 0}`" name="external">
        <div class="external-list">
          <el-tag v-for="table in result.externalTables || []" :key="table" effect="plain">{{ table }}</el-tag>
          <el-empty v-if="!result.externalTables?.length" description="没有未匹配的外部输入表" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-empty v-else description="点击“扫描 SQL 依赖”开始分析迁移后的 SQL" />
  </section>
</template>

<script setup>
import axios from 'axios'
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const result = ref(null)
const loading = ref(false)
const applying = ref(false)
const tab = ref('relations')
const applyOptions = reactive({ sameWorkflow: true, crossWorkflow: true })
const hasApplySelection = computed(() => applyOptions.sameWorkflow || applyOptions.crossWorkflow)

const statusType = status => {
  if (status === 'READY') return 'success'
  if (status === 'EXISTING') return 'info'
  return 'danger'
}
const statusLabel = status => ({ READY: '可应用', EXISTING: '已存在', BLOCKED: '已阻塞', CONFLICT: '冲突' }[status] || status)

async function scan() {
  loading.value = true
  try {
    const { data } = await axios.get('/api/sql-dependencies/scan')
    result.value = data
    ElMessage.success(`分析完成：识别 ${data.taskCount || 0} 个 SQL 任务，${data.readyCount || 0} 条依赖可应用`)
  } catch (e) { showError(e) } finally { loading.value = false }
}

async function applyDependencies() {
  await ElMessageBox.confirm(
    '将把 READY 状态的 SQL 血缘关系写入 DataSphere。同工作流写入 DAG，跨工作流写为 disabled 工作流依赖；不会启用调度。是否继续？',
    '确认应用 SQL 前置依赖',
    { type: 'warning', confirmButtonText: '确认应用', cancelButtonText: '取消' }
  )
  applying.value = true
  try {
    const { data } = await axios.post('/api/sql-dependencies/apply', applyOptions)
    data.success ? ElMessage.success(data.message) : ElMessage.warning(data.message)
    await scan()
  } catch (e) { showError(e) } finally { applying.value = false }
}

function showError(e) {
  ElMessage.error(e?.response?.data?.message || e?.message || String(e))
}
</script>

<style scoped>
.lineage-panel{padding:20px}
.lineage-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px}
.lineage-heading h2{margin:0 0 6px;font-size:18px}
.lineage-heading p{margin:0;color:#6b7280;line-height:1.6}
.metric-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin-bottom:16px}
.metric-grid>div{padding:14px 16px;border:1px solid #e5e7eb;border-radius:8px;background:#fff}
.metric-grid span{display:block;color:#6b7280;font-size:13px}
.metric-grid strong{display:block;margin-top:6px;font-size:24px}
.apply-bar{display:flex;align-items:center;gap:20px;margin:16px 0;padding:14px 16px;border:1px solid #e5e7eb;border-radius:8px;background:#fafafa}
.spacer{flex:1}
.result-tabs{margin-top:8px}
.table-list{line-height:1.6;word-break:break-all}
.external-list{display:flex;flex-wrap:wrap;gap:10px;padding:16px 0}
@media(max-width:900px){.metric-grid{grid-template-columns:repeat(2,1fr)}.lineage-heading,.apply-bar{align-items:flex-start;flex-direction:column}.spacer{display:none}}
</style>
