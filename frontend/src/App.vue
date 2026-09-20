<template>
  <div class="page-shell">
    <header class="topbar">
      <div>
        <div class="eyebrow">DATASPHERE MIGRATION TOOL</div>
        <h1>DolphinScheduler 3.1.9 迁移控制台</h1>
        <p>独立迁移工具 · 分析 / 试运行 / 正式迁移 / 问题跟踪</p>
      </div>
      <div class="header-actions">
        <el-button @click="loadAll">刷新</el-button>
        <el-button v-if="mainTab === 'analysis'" type="primary" :loading="actionLoading" @click="startAnalyze">开始分析</el-button>
      </div>
    </header>

    <el-tabs v-model="mainTab" class="main-tabs">
      <el-tab-pane label="元数据分析" name="analysis">
    <section class="panel action-panel">
      <div class="section-title">
        <div><h2>迁移操作</h2><span>正式迁移不会自动发布或上线工作流</span></div>
      </div>
      <div class="action-row">
        <el-button type="primary" :loading="actionLoading" @click="startAnalyze">1. 分析元数据</el-button>
        <el-button :loading="actionLoading" :disabled="!scopeReady" @click="startDryRun">2. 试运行</el-button>
        <el-button type="danger" plain :loading="actionLoading" :disabled="!scopeReady" @click="startMigration">3. 正式迁移</el-button>
        <span class="action-hint">建议先处理 ERROR 问题，再执行正式迁移。</span>
      </div>
    </section>

    <section v-if="latestAnalysisId" class="panel scope-panel">
      <div class="section-title">
        <div><h2>迁移范围</h2><span>默认全选，可按项目或工作流取消；Task / DAG / Schedule 会自动跟随工作流范围</span></div>
        <div class="scope-actions">
          <el-button size="small" @click="selectAllScope">全选</el-button>
          <el-button size="small" @click="selectOnlineScope">只选已上线</el-button>
          <el-button size="small" @click="clearScope">清空</el-button>
        </div>
      </div>
      <div class="scope-summary">
        <span>分析 Run #{{ latestAnalysisId }}</span>
        <strong>已选 {{ selectedWorkflowCount }} / {{ migrationScope.workflowCount || 0 }} 个工作流</strong>
        <span>项目 {{ migrationScope.projectCount || 0 }} 个 · 任务引用 {{ migrationScope.taskCount || 0 }} 个</span>
      </div>
      <el-skeleton v-if="scopeLoading" :rows="3" animated />
      <el-tree v-else ref="scopeTreeRef" class="scope-tree" :data="scopeTreeData" show-checkbox
               node-key="id" default-expand-all :props="{ children: 'children', label: 'label' }" @check="updateScopeSelection">
        <template #default="{ data }">
          <div class="scope-node">
            <div>
              <strong>{{ data.label }}</strong>
              <span v-if="data.kind === 'PROJECT'">{{ data.workflowCount }} 个工作流 · {{ data.taskCount }} 个任务引用</span>
              <span v-else>{{ data.taskCount }} 个任务 · {{ (data.taskTypes || []).join(' / ') || '无任务' }}</span>
            </div>
            <el-tag v-if="data.kind === 'WORKFLOW'" size="small" :type="data.online ? 'success' : 'info'">{{ data.online ? '已上线' : '未上线' }}</el-tag>
          </div>
        </template>
      </el-tree>
    </section>

    <section v-if="selectedRun" class="panel progress-panel">
      <div class="run-heading">
        <div>
          <div class="eyebrow">RUN #{{ selectedRun.id }} · {{ selectedRun.operation }}</div>
          <h2>{{ runTitle(selectedRun) }}</h2>
          <p>{{ selectedRun.message }}</p>
        </div>
        <div class="run-actions">
          <el-tag :type="statusType(selectedRun.status)">{{ selectedRun.status }}</el-tag>
          <el-button v-if="isRunning(selectedRun)" type="danger" text @click="cancelRun(selectedRun.id)">取消</el-button>
        </div>
      </div>
      <el-progress :percentage="selectedRun.progress" :stroke-width="12" />
      <div class="metric-grid">
        <div class="metric"><span>总对象</span><strong>{{ selectedRun.totalObjects }}</strong></div>
        <div class="metric"><span>已处理</span><strong>{{ selectedRun.processedObjects }}</strong></div>
        <div class="metric"><span>成功</span><strong>{{ selectedRun.successCount }}</strong></div>
        <div class="metric warn"><span>警告</span><strong>{{ selectedRun.warningCount }}</strong></div>
        <div class="metric danger"><span>失败/错误</span><strong>{{ selectedRun.failureCount }}</strong></div>
      </div>
    </section>

    <section class="content-grid">
      <div class="panel runs-panel">
        <div class="section-title"><div><h2>任务记录</h2><span>最近 100 次执行</span></div></div>
        <el-table :data="runs" height="420" highlight-current-row @row-click="selectRun">
          <el-table-column prop="id" label="#" width="70" />
          <el-table-column prop="operation" label="类型" width="90" />
          <el-table-column prop="status" label="状态" min-width="150"><template #default="s"><el-tag size="small" :type="statusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="progress" label="进度" width="90"><template #default="s">{{ s.row.progress }}%</template></el-table-column>
          <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="panel detail-panel">
        <el-tabs v-model="activeTab">
          <el-tab-pane :label="`迁移对象 ${items.length}`" name="items">
            <el-table :data="items" height="365">
              <el-table-column prop="objectType" label="类型" width="105" />
              <el-table-column prop="objectName" label="名称" min-width="190" show-overflow-tooltip />
              <el-table-column prop="taskType" label="Task" width="100" />
              <el-table-column prop="status" label="状态" width="145"><template #default="s"><el-tag size="small" :type="statusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
              <el-table-column prop="message" label="说明" min-width="230" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`问题 ${issues.filter(i => i.status === 'OPEN').length}`" name="issues">
            <el-table :data="issues" height="365">
              <el-table-column prop="severity" label="级别" width="90"><template #default="s"><el-tag size="small" :type="s.row.severity === 'ERROR' ? 'danger' : 'warning'">{{ s.row.severity }}</el-tag></template></el-table-column>
              <el-table-column prop="issueCode" label="问题码" min-width="170" />
              <el-table-column prop="objectName" label="对象" min-width="160" show-overflow-tooltip />
              <el-table-column prop="message" label="问题" min-width="260" show-overflow-tooltip />
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column label="操作" width="90"><template #default="s"><el-button v-if="s.row.status === 'OPEN'" text type="primary" @click="resolveIssue(s.row.id)">已处理</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`事件 ${events.length}`" name="events">
            <div class="event-list">
              <div v-for="e in events" :key="e.id" class="event-row">
                <span class="event-time">{{ formatTime(e.createdAt) }}</span><el-tag size="small" :type="e.level === 'ERROR' ? 'danger' : e.level === 'WARN' ? 'warning' : 'info'">{{ e.level }}</el-tag>
                <span class="event-phase">{{ e.phase }}</span><span>{{ e.message }}</span>
              </div>
              <el-empty v-if="!events.length" description="暂无事件" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>
      </el-tab-pane>
      <el-tab-pane label="数据库设置" name="settings">
    <section class="panel config-panel">
      <div class="section-title">
        <div><h2>连接配置</h2><span>源端只读，目标端使用 DataSphere 账号登录后通过 REST API 写入</span></div>
      </div>
      <div class="config-grid">
          <div class="config-block">
            <div class="block-title">DolphinScheduler 元数据库</div>
            <el-form label-position="top">
              <el-form-item label="JDBC URL"><el-input v-model="settings.sourceJdbcUrl" /></el-form-item>
              <div class="two-col">
                <el-form-item label="用户名"><el-input v-model="settings.sourceUsername" /></el-form-item>
                <el-form-item label="密码"><el-input v-model="settings.sourcePassword" type="password" show-password placeholder="留空表示不修改" /></el-form-item>
              </div>
              <el-button :loading="testSourceLoading" @click="testSource">测试源端连接</el-button>
              <span class="configured" v-if="settings.sourcePasswordConfigured">已配置密码</span>
            </el-form>
          </div>
          <div class="config-block">
            <div class="block-title">DataSphere</div>
            <el-form label-position="top">
              <el-form-item label="Base URL"><el-input v-model="settings.targetBaseUrl" /></el-form-item>
              <div class="two-col">
                <el-form-item label="登录用户名"><el-input v-model="settings.targetUsername" placeholder="例如 admin" /></el-form-item>
                <el-form-item label="登录密码"><el-input v-model="settings.targetPassword" type="password" show-password placeholder="留空表示不修改" /></el-form-item>
              </div>
              <el-button :loading="testTargetLoading" @click="testTarget">登录并测试 DataSphere</el-button>
              <span class="configured" v-if="settings.targetPasswordConfigured">已配置密码</span>
            </el-form>
          </div>
          <div class="config-footer">
            <el-button type="primary" :loading="saveLoading" @click="saveSettings">保存连接配置</el-button>
            <span>敏感字段留空时保留原配置，不会回显明文。</span>
          </div>
      </div>
    </section>
      </el-tab-pane>
    </el-tabs>
  </div>

</template>

<script setup>
import axios from 'axios'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const settings = ref({ sourceJdbcUrl: '', sourceUsername: '', sourcePassword: '', targetBaseUrl: '', targetUsername: '', targetPassword: '' })
const runs = ref([])
const selectedRun = ref(null)
const items = ref([])
const issues = ref([])
const events = ref([])
const mainTab = ref('analysis')
const activeTab = ref('items')
const saveLoading = ref(false)
const testSourceLoading = ref(false)
const testTargetLoading = ref(false)
const actionLoading = ref(false)
const migrationScope = ref({ projectCount: 0, workflowCount: 0, taskCount: 0, projects: [] })
const scopeTreeRef = ref(null)
const scopeLoading = ref(false)
const scopeAnalysisId = ref(null)
const selectedWorkflowCount = ref(0)
let timer

const latestAnalysisId = computed(() => {
  const run = runs.value.find(r => r.operation === 'ANALYZE' && String(r.status).startsWith('COMPLETED'))
  return run?.id || null
})

const scopeTreeData = computed(() => (migrationScope.value.projects || []).map(p => ({
  id: `P:${p.projectCode}`, kind: 'PROJECT', label: p.projectName, workflowCount: p.workflowCount, taskCount: p.taskCount,
  children: (p.workflows || []).map(w => ({ id: `W:${w.workflowCode}`, kind: 'WORKFLOW', label: w.workflowName,
    workflowCode: w.workflowCode, online: w.online, taskCount: w.taskCount, taskTypes: w.taskTypes }))
})))
const scopeReady = computed(() => !!latestAnalysisId.value && scopeAnalysisId.value === latestAnalysisId.value && selectedWorkflowCount.value > 0)

const isRunning = run => ['QUEUED', 'RUNNING'].includes(run?.status)
const statusType = status => {
  if (['FAILED', 'BLOCKED', 'MANUAL_REQUIRED'].includes(status)) return 'danger'
  if (String(status).includes('ISSUES') || ['WARN', 'SKIPPED'].includes(status)) return 'warning'
  if (['SUCCESS', 'COMPLETED', 'DRY_RUN_OK'].includes(status)) return 'success'
  return 'info'
}

const runTitle = run => run.operation === 'ANALYZE' ? '元数据分析' : run.dryRun ? '迁移试运行' : '正式迁移'
const formatTime = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'

async function loadSettings() {
  const { data } = await axios.get('/api/settings')
  settings.value = { ...data, sourcePassword: '', targetPassword: '' }
}
async function saveSettings() {
  saveLoading.value = true
  try {
    const { data } = await axios.put('/api/settings', settings.value)
    settings.value = { ...data, sourcePassword: '', targetPassword: '' }
    ElMessage.success('连接配置已保存')
  } catch (e) { showError(e) } finally { saveLoading.value = false }
}
async function testSource() {
  testSourceLoading.value = true
  try { await saveSettings(); const { data } = await axios.post('/api/settings/test-source'); data.success ? ElMessage.success(data.message) : ElMessage.error(data.message) }
  catch (e) { showError(e) } finally { testSourceLoading.value = false }
}
async function testTarget() {
  testTargetLoading.value = true
  try { await saveSettings(); const { data } = await axios.post('/api/settings/test-target'); data.success ? ElMessage.success(data.message) : ElMessage.error(data.message) }
  catch (e) { showError(e) } finally { testTargetLoading.value = false }
}

async function loadRuns() {
  const { data } = await axios.get('/api/runs')
  runs.value = data
  if (!selectedRun.value && data.length) await selectRun(data[0])
  await ensureScope()
}

async function ensureScope() {
  const analysisId = latestAnalysisId.value
  if (!analysisId || scopeAnalysisId.value === analysisId || scopeLoading.value) return
  scopeLoading.value = true
  try {
    const { data } = await axios.get('/api/scope')
    migrationScope.value = data
    scopeAnalysisId.value = analysisId
    await nextTick()
    selectAllScope()
  } catch (e) { showError(e) } finally { scopeLoading.value = false }
}
function workflowLeafIds(predicate = () => true) {
  return scopeTreeData.value.flatMap(p => p.children || []).filter(predicate).map(w => w.id)
}
function selectAllScope() {
  scopeTreeRef.value?.setCheckedKeys(workflowLeafIds())
  updateScopeSelection()
}
function selectOnlineScope() {
  scopeTreeRef.value?.setCheckedKeys(workflowLeafIds(w => w.online))
  updateScopeSelection()
}
function clearScope() {
  scopeTreeRef.value?.setCheckedKeys([])
  updateScopeSelection()
}
function selectedWorkflowCodes() {
  const keys = scopeTreeRef.value?.getCheckedKeys(true) || []
  return keys.filter(k => String(k).startsWith('W:')).map(k => Number(String(k).slice(2))).filter(Number.isFinite)
}
function updateScopeSelection() { selectedWorkflowCount.value = selectedWorkflowCodes().length }

async function selectRun(run) {
  selectedRun.value = run
  await loadRunDetail(run.id)
}
async function loadRunDetail(id) {
  const [runRes, itemRes, issueRes, eventRes] = await Promise.all([
    axios.get(`/api/runs/${id}`), axios.get(`/api/runs/${id}/items`),
    axios.get(`/api/runs/${id}/issues`), axios.get(`/api/runs/${id}/events`)
  ])
  selectedRun.value = runRes.data
  items.value = itemRes.data
  issues.value = issueRes.data
  events.value = eventRes.data
}
async function loadAll() {
  try {
    await loadRuns()
    if (selectedRun.value) await loadRunDetail(selectedRun.value.id)
  } catch (e) { showError(e) }
}

async function startAnalyze() {
  actionLoading.value = true
  try {
    await saveSettings()
    const { data } = await axios.post('/api/runs/analyze')
    ElMessage.success(data.message)
    await loadRuns(); const run = runs.value.find(r => r.id === data.runId); if (run) await selectRun(run)
  } catch (e) { showError(e) } finally { actionLoading.value = false }
}
async function startDryRun() { await startMigrate(true) }
async function startMigration() {
  await ElMessageBox.confirm(`将迁移已选择的 ${selectedWorkflowCount.value} 个工作流，并在 DataSphere 创建对应目录、开发文件、工作流和 disabled 调度。不会自动发布/上线。是否继续？`, '确认正式迁移', { type: 'warning' })
  await startMigrate(false)
}

async function startMigrate(dryRun) {
  actionLoading.value = true
  try {
    const workflowCodes = selectedWorkflowCodes()
    if (!workflowCodes.length) { ElMessage.warning('至少选择一个工作流'); return }
    const { data } = await axios.post('/api/runs/migrate', { analysisRunId: latestAnalysisId.value, dryRun, migrateAll: false, workflowCodes })
    ElMessage.success(data.message)
    await loadRuns(); const run = runs.value.find(r => r.id === data.runId); if (run) await selectRun(run)
  } catch (e) { if (e !== 'cancel') showError(e) } finally { actionLoading.value = false }
}
async function cancelRun(id) {
  try { await axios.post(`/api/runs/${id}/cancel`); ElMessage.success('已请求取消'); await loadRunDetail(id) }
  catch (e) { showError(e) }
}
async function resolveIssue(id) {
  try { await axios.post(`/api/issues/${id}/resolve`); ElMessage.success('已标记处理'); await loadRunDetail(selectedRun.value.id) }
  catch (e) { showError(e) }
}
function showError(e) {
  const message = e?.response?.data?.message || e?.message || String(e)
  ElMessage.error(message)
}

onMounted(async () => {
  try { await loadSettings(); await loadAll() } catch (e) { showError(e) }
  timer = setInterval(async () => {
    try {
      await loadRuns()
      if (selectedRun.value && isRunning(selectedRun.value)) await loadRunDetail(selectedRun.value.id)
    } catch (_) { }
  }, 2500)
})
onUnmounted(() => clearInterval(timer))
</script>
