/** agent 名字 → 界面显示名。改名字只改这里。 */
export const AGENT_LABEL: Record<string, string> = {
  manager: '分诊',
  doctor: '全科医生',
  pharma: '药品助手',
  report: '报告解读',
  care: '就医指引',
}

export function agentLabel(name?: string): string {
  if (!name) return ''
  return AGENT_LABEL[name] ?? name
}
