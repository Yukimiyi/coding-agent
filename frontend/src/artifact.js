/** 会改变会话项目文件内容的工具名称。 */
const MUTATION_TOOLS = new Set(['write_file', 'edit_file', 'delete_file'])

/**
 * 判断最近一次运行是否产生了可下载的智能体修改结果。
 *
 * <p>{@code artifactAvailable} 只说明项目目录中存在文件，上传原项目也会满足；
 * 只有成功完成的运行实际调用过文件变更工具，界面才应提示“修改结果可下载”。</p>
 *
 * @param {object|null} conversation 当前会话摘要。
 * @param {object|null} latestRun 当前会话最近一次运行。
 * @returns {boolean} 最近一次运行成功产生文件修改时返回 {@code true}。
 */
export function hasAgentArtifact(conversation, latestRun) {
  if (conversation?.mode !== 'CODE' || !conversation.artifactAvailable) return false
  if (latestRun?.status !== 'COMPLETED') return false

  const toolSteps = latestRun.toolSteps || latestRun.result?.toolSteps || []
  return toolSteps.some((step) => step?.success && MUTATION_TOOLS.has(step.toolName))
}
