<template>
  <section class="panel cleanup-panel">
    <div class="cleanup-heading">
      <div>
        <h2>清理 DataForge 迁移任务</h2>
        <p>只清理由本迁移工具创建并记录在 migration_object_map 中的工作流与开发任务，不删除项目、数据源、用户或系统配置。</p>
      </div>
      <el-tag type="danger" effect="plain">危险操作</el-tag>
    </div>

    <div class="cleanup-options">
      <el-checkbox v-model="allSelected" :indeterminate="indeterminate" @change="toggleAll">全部</el-checkbox>
      <el-checkbox v-model="selected.development" @change="refreshPreview">数据开发</el-checkbox>
      <el-checkbox v-model="selected.workflows" @change="refreshPreview">任务流</el-checkbox>
    </div>

    <div class="cleanup-summary">
      <div><span>数据开发</span><strong>{{ preview.developmentTaskCount || 0 }}</strong></div>
      <div><span>任务流</span><strong>{{ preview.workflowCount || 0 }}</strong></div>
      <div><span>本次将清除</span><strong>{{ preview.totalSelectedObjects || 0 }}</strong></div>
    </div>

    <el-alert
      title="一键清除全部会先删除跨工作流依赖，已发布工作流会自动下线后删除；开发任务会在解除工作流引用后下线、移入回收箱并彻底删除。顺序固定为：工作流 → 数据开发。"
      type="warning" :closable="false" show-icon />

    <div class="cleanup-actions">
      <el-button :loading="previewLoading" @click="refreshPreview">重新统计</el-button>
      <el-button type="warning" :loading="offlineLoading" :disabled="!selected.development || !preview.developmentTaskCount" @click="runOfflineDevelopment">
        一键下线数据开发
      </el-button>
      <el-button :loading="cleanupLoading" :disabled="!hasSelection || !preview.totalSelectedObjects" @click="runCleanup">
        按勾选范围清除
      </el-button>
      <el-button type="danger" :loading="cleanupAllLoading" @click="runCleanupAll">
        一键清除全部迁移数据
      </el-button>
    </div>

    <el-table v-if="failures.length" :data="failures" class="failure-table">
      <el-table-column prop="objectType" label="类型" width="120" />
      <el-table-column prop="objectName" label="对象" min-width="180" />
      <el-table-column prop="message" label="失败原因" min-width="360" show-overflow-tooltip />
    </el-table>
  </section>
</template>

<script setup>
import axios from 'axios'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const selected = reactive({ development: true, workflows: true })
const preview = reactive({ developmentTaskCount: 0, recycledDevelopmentTaskCount: 0, workflowCount: 0, totalSelectedObjects: 0 })
const previewLoading = ref(false)
const offlineLoading = ref(false)
const cleanupLoading = ref(false)
const cleanupAllLoading = ref(false)
const failures = ref([])
const allSelected = ref(true)

const hasSelection = computed(() => selected.development || selected.workflows)
const indeterminate = computed(() => hasSelection.value && !(selected.development && selected.workflows))

watch(() => [selected.development, selected.workflows], () => {
  allSelected.value = selected.development && selected.workflows
})

function payload() {
  return { development: selected.development, workflows: selected.workflows }
}

function toggleAll(value) {
  selected.development = !!value
  selected.workflows = !!value
  refreshPreview()
}

async function refreshPreview() {
  if (!hasSelection.value) {
    Object.assign(preview, { developmentTaskCount: 0, recycledDevelopmentTaskCount: 0, workflowCount: 0, totalSelectedObjects: 0 })
    return
  }
  previewLoading.value = true
  try {
    const { data } = await axios.post('/api/cleanup/preview', payload())
    Object.assign(preview, data)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || String(e))
  } finally {
    previewLoading.value = false
  }
}

async function runOfflineDevelopment() {
  try {
    await ElMessageBox.confirm(
      `将检查本迁移工具记录的 ${preview.developmentTaskCount || 0} 个数据开发任务，并把已经发布上线的任务批量下线。是否继续？`,
      '确认一键下线',
      { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' }
    )
  } catch (_) { return }
  offlineLoading.value = true
  failures.value = []
  try {
    const { data } = await axios.post('/api/cleanup/offline-development')
    failures.value = data.failures || []
    data.success ? ElMessage.success(data.message) : ElMessage.warning(data.message)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || String(e))
  } finally {
    offlineLoading.value = false
  }
}

async function runCleanup() {
  const labels = [selected.development ? '数据开发' : '', selected.workflows ? '任务流' : ''].filter(Boolean).join('、')
  try {
    await ElMessageBox.confirm(
      `将清除本迁移工具创建的 ${labels}，当前统计 ${preview.totalSelectedObjects || 0} 个对象。此操作不可通过迁移工具恢复，是否继续？`,
      '确认清除 DataForge 迁移数据',
      { type: 'error', confirmButtonText: '确认清除', cancelButtonText: '取消' }
    )
  } catch (_) { return }
  cleanupLoading.value = true
  failures.value = []
  try {
    const { data } = await axios.post('/api/cleanup', payload())
    failures.value = data.failures || []
    data.success ? ElMessage.success(data.message) : ElMessage.warning(data.message)
    await refreshPreview()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || String(e))
  } finally {
    cleanupLoading.value = false
  }
}

async function runCleanupAll() {
  let allPreview
  try {
    const { data } = await axios.post('/api/cleanup/preview', { development: true, workflows: true })
    allPreview = data
    if (!(data.totalSelectedObjects > 0)) {
      ElMessage.info('当前没有本迁移工具记录的 DataForge 迁移对象')
      return
    }
    await ElMessageBox.confirm(
      `将彻底清除本工具记录的全部 DataForge 迁移数据：工作流 ${data.workflowCount || 0} 个、数据开发任务 ${data.developmentTaskCount || 0} 个。会自动处理工作流依赖、下线和回收箱彻底删除。是否继续？`,
      '确认一键清除全部迁移数据',
      { type: 'error', confirmButtonText: '彻底清除', cancelButtonText: '取消' }
    )
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.response?.data?.message || e?.message || String(e))
    return
  }
  cleanupAllLoading.value = true
  failures.value = []
  try {
    const { data } = await axios.post('/api/cleanup/all')
    failures.value = data.failures || []
    data.success ? ElMessage.success(data.message) : ElMessage.warning(data.message)
    selected.development = true
    selected.workflows = true
    allSelected.value = true
    await refreshPreview()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || String(e))
  } finally {
    cleanupAllLoading.value = false
  }
}

onMounted(refreshPreview)
</script>

<style scoped>
.cleanup-panel{padding:20px}
.cleanup-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}
.cleanup-heading h2{margin:0 0 6px;font-size:18px}
.cleanup-heading p{margin:0;color:#6b7280;line-height:1.6}
.cleanup-options{display:flex;align-items:center;gap:22px;margin:22px 0 16px;padding:14px 16px;border:1px solid #e5e7eb;border-radius:8px;background:#fafafa}
.cleanup-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:16px}
.cleanup-summary>div{padding:14px 16px;border:1px solid #e5e7eb;border-radius:8px;background:#fff}
.cleanup-summary span{display:block;color:#6b7280;font-size:13px}
.cleanup-summary strong{display:block;margin-top:6px;font-size:24px}
.cleanup-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:16px;flex-wrap:wrap}
.failure-table{margin-top:16px}
@media(max-width:760px){.cleanup-summary{grid-template-columns:1fr}.cleanup-options{align-items:flex-start;flex-direction:column;gap:10px}}
</style>
