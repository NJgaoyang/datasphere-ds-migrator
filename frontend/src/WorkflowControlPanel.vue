<template>
  <section class="panel control-panel">
    <div class="control-heading">
      <div>
        <h2>运行控制</h2>
        <p>只操作本迁移工具映射的 Workflow。上线按“开发任务生产版本 → Workflow 发布 → 生产切换检查 → Native Scheduler 上线”执行。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新状态</el-button>
    </div>

    <div class="metric-grid">
      <div><span>已迁移 Workflow</span><strong>{{ snapshot.totalCount || 0 }}</strong></div>
      <div><span>已发布</span><strong>{{ snapshot.publishedCount || 0 }}</strong></div>
      <div><span>调度在线</span><strong>{{ snapshot.onlineCount || 0 }}</strong></div>
      <div><span>可运行 / 可切换</span><strong>{{ snapshot.readyCount || 0 }}</strong></div>
    </div>

    <el-alert type="info" :closable="false" show-icon
      title="一键下线只关闭 Native Scheduler 自动调度，不取消 Workflow 发布；下线后仍可手工运行。无调度配置的 Workflow 上线时只发布，不会自动创建默认 Cron。" />

    <div class="action-bar">
      <span>已选择 <strong>{{ selectedIds.length }}</strong> / {{ snapshot.totalCount || 0 }} 个 Workflow</span>
      <span class="spacer" />
      <el-button type="success" :loading="actionLoading === 'online'" :disabled="!selectedIds.length || !!actionLoading" @click="onlineSelected">一键上线</el-button>
      <el-button type="warning" plain :loading="actionLoading === 'offline'" :disabled="!selectedIds.length || !!actionLoading" @click="offlineSelected">一键下线</el-button>
      <el-button type="primary" :loading="actionLoading === 'run'" :disabled="!selectedIds.length || !!actionLoading" @click="runSelected">跑任务</el-button>
    </div>

    <el-table ref="tableRef" :data="snapshot.workflows || []" height="480" row-key="workflowId" @selection-change="selectionChanged">
      <el-table-column type="selection" width="48" reserve-selection />
      <el-table-column prop="name" label="Workflow" min-width="190" show-overflow-tooltip />
      <el-table-column prop="workflowCode" label="编码" min-width="170" show-overflow-tooltip />
      <el-table-column label="定义状态" width="110">
        <template #default="s"><el-tag size="small" :type="s.row.definitionStatus === 'PUBLISHED' ? 'success' : s.row.definitionStatus === 'MISSING' ? 'danger' : 'info'">{{ definitionLabel(s.row.definitionStatus) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="调度" width="110">
        <template #default="s">
          <el-tag v-if="!s.row.scheduleConfigured" size="small" type="info">无调度</el-tag>
          <el-tag v-else size="small" :type="s.row.scheduleEnabled ? 'success' : 'warning'">{{ s.row.scheduleEnabled ? '在线' : '离线' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="切换检查" min-width="230" show-overflow-tooltip>
        <template #default="s">
          <span v-if="s.row.preflightReady === true" class="ok">通过</span>
          <span v-else-if="s.row.preflightReady === false" class="bad">阻塞</span>
          <span v-else class="muted">待发布</span>
          <span class="detail">{{ s.row.preflightMessage || s.row.message }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="s"><el-button text type="primary" :disabled="!!actionLoading" @click="runOne(s.row)">运行</el-button></template>
      </el-table-column>
    </el-table>

    <div v-if="lastResult" class="result-block">
      <div class="result-title">
        <strong>{{ actionLabel(lastResult.action) }}</strong>
        <span>{{ lastResult.message }}</span>
      </div>
      <el-table :data="lastResult.items || []" max-height="320">
        <el-table-column prop="name" label="Workflow" min-width="180" show-overflow-tooltip />
        <el-table-column label="结果" width="100"><template #default="s"><el-tag size="small" :type="s.row.success ? 'success' : 'danger'">{{ s.row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="instanceId" label="实例编号" min-width="190" show-overflow-tooltip />
        <el-table-column prop="message" label="说明" min-width="280" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>

<script setup>
import axios from 'axios'
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const snapshot = ref({ totalCount: 0, publishedCount: 0, onlineCount: 0, readyCount: 0, workflows: [] })
const loading = ref(false)
const actionLoading = ref('')
const selectedIds = ref([])
const tableRef = ref(null)
const lastResult = ref(null)

const definitionLabel = status => ({ DRAFT: '草稿', PUBLISHED: '已发布', MISSING: '不存在' }[status] || status || '-')
const actionLabel = action => ({ ONLINE: '一键上线结果', OFFLINE: '一键下线结果', RUN: '运行结果' }[action] || action)

async function load(selectAll = false) {
  loading.value = true
  try {
    const { data } = await axios.get('/api/workflow-control')
    snapshot.value = data
    if (selectAll) {
      await nextTick()
      tableRef.value?.clearSelection()
      ;(snapshot.value.workflows || []).forEach(row => tableRef.value?.toggleRowSelection(row, true))
    }
  } catch (e) { showError(e) } finally { loading.value = false }
}

function selectionChanged(rows) { selectedIds.value = rows.map(row => row.workflowId) }

async function onlineSelected() {
  await ElMessageBox.confirm(
    `将上线所选 ${selectedIds.value.length} 个 Workflow：必要时先上线并发布迁移工具管理的开发任务，再发布 Workflow；有 Native Scheduler 调度的任务会通过生产切换检查后启用。不会绕过旧 Quartz 双跑保护。是否继续？`,
    '确认一键上线', { type: 'warning', confirmButtonText: '确认上线', cancelButtonText: '取消' })
  await execute('online', selectedIds.value)
}

async function offlineSelected() {
  await ElMessageBox.confirm(
    `将关闭所选 ${selectedIds.value.length} 个 Workflow 的 Native Scheduler 自动调度。已发布定义和开发任务生产版本会保留，仍可手动运行。是否继续？`,
    '确认一键下线', { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' })
  await execute('offline', selectedIds.value)
}

async function runSelected() {
  await ElMessageBox.confirm(
    `将立即提交所选 ${selectedIds.value.length} 个 Workflow 的手工运行实例。手工运行不要求调度在线，但 Workflow 必须已发布。是否继续？`,
    '确认跑任务', { type: 'warning', confirmButtonText: '立即运行', cancelButtonText: '取消' })
  await execute('run', selectedIds.value)
}

async function runOne(row) {
  await ElMessageBox.confirm(`将立即手工运行 Workflow「${row.name}」。是否继续？`, '确认运行', { type: 'warning', confirmButtonText: '立即运行', cancelButtonText: '取消' })
  await execute('run', [row.workflowId])
}

async function execute(action, ids) {
  actionLoading.value = action
  try {
    const { data } = await axios.post(`/api/workflow-control/${action}`, { workflowIds: ids })
    lastResult.value = data
    data.failureCount ? ElMessage.warning(data.message) : ElMessage.success(data.message)
    await load(false)
  } catch (e) { showError(e) } finally { actionLoading.value = '' }
}

function showError(e) { ElMessage.error(e?.response?.data?.message || e?.message || String(e)) }

onMounted(() => load(true))
</script>

<style scoped>
.control-panel{padding:20px}
.control-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px}
.control-heading h2{margin:0 0 6px;font-size:18px}.control-heading p{margin:0;color:#6b7280;line-height:1.6}
.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:16px}
.metric-grid>div{padding:14px 16px;border:1px solid #e5e7eb;border-radius:8px;background:#fff}.metric-grid span{display:block;color:#6b7280;font-size:13px}.metric-grid strong{display:block;margin-top:6px;font-size:24px}
.action-bar{display:flex;align-items:center;gap:10px;margin:16px 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px;background:#fafafa}.spacer{flex:1}
.ok{color:#15803d;font-weight:600}.bad{color:#b91c1c;font-weight:600}.muted{color:#6b7280}.detail{margin-left:8px;color:#6b7280}
.result-block{margin-top:18px;padding-top:16px;border-top:1px solid #e5e7eb}.result-title{display:flex;gap:12px;align-items:center;margin-bottom:10px}.result-title span{color:#6b7280}
@media(max-width:900px){.metric-grid{grid-template-columns:repeat(2,1fr)}.control-heading,.action-bar{align-items:flex-start;flex-direction:column}.spacer{display:none}}
</style>
