"use client";
import { taskLabel } from "./studio-model";
import { conversationStates, type Conversation } from "./chief-data";
import type { Snapshot, Task } from "./types";

export const needsAttention = (task: Task) =>
  ["WAITING_APPROVAL", "BLOCKED", "FAILED"].includes(task.status);
export const conversationAttention = (conversation: Conversation) =>
  ["PLAN_READY", "WAITING_RESULT", "NEEDS_HELP"].includes(conversation.state);
export function nextTaskAction(task: Task) {
  if (task.status === "WAITING_APPROVAL") return task.startedAt ? "변경 내용·테스트·리뷰 결과를 확인하고 승인하세요." : "실행 범위와 계획을 확인하고 승인하세요.";
  if (task.status === "BLOCKED") return "환경·인증·필요한 Skill을 확인하세요.";
  return "실패 원인과 실행 기록을 확인하세요.";
}
export function ProjectContext({snapshot, projectId, setProject, conversations, navigate, openConversation, addProject}: {
  snapshot: Snapshot; projectId: string; setProject: (id: string) => void;
  conversations: Conversation[]; navigate: (page: string) => void;
  openConversation: (conversation: Conversation) => void; addProject: () => void;
}) {
  const tasks = snapshot.tasks.filter(t => t.projectId === projectId);
  const active = tasks.filter(t => ["RUNNING", "VERIFYING", "ANALYZING", "QUEUED"].includes(t.status));
  const pendingConversations = conversations.filter(c => c.projectId === projectId && conversationAttention(c));
  const pendingTasks = tasks.filter(needsAttention);
  const liveConversations = conversations.filter(c => c.projectId === projectId && ["THINKING", "RUNNING"].includes(c.state));
  return <section className="project-context" aria-label="현재 프로젝트와 다음 행동">
    <label>현재 프로젝트<select aria-label="현재 프로젝트" value={projectId} onChange={e => setProject(e.target.value)}>
      {!snapshot.projects.length && <option value="">연결된 프로젝트 없음</option>}
      {snapshot.projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
    </select></label>
    <div role="status"><strong>{projectId ? `진행 중 ${active.length}개 작업 · 확인할 대화 ${pendingConversations.length}개 · 확인할 작업 ${pendingTasks.length}개` : "프로젝트 연결이 필요합니다"}</strong>
      <p>{!projectId ? "로컬 저장소를 연결하고 총괄에게 하고 싶은 일을 이야기하세요." : pendingConversations.length || pendingTasks.length ? "승인 요청이나 진행을 막는 문제를 확인하세요." : active.length || liveConversations.length ? "총괄과 전문가 팀이 진행 중입니다. 대화에서 진행 상황을 확인하세요." : "총괄과 다음 작업의 목표와 범위를 정하세요."}</p></div>
    <button className="button primary" onClick={() => !projectId ? addProject() : pendingConversations[0] ? openConversation(pendingConversations[0]) : pendingTasks.length ? navigate("attention") : navigate("chief")}>{!projectId ? "프로젝트 연결" : pendingConversations.length || pendingTasks.length ? "확인할 일 열기" : "총괄과 대화하기"}</button>
  </section>;
}
export function Attention({snapshot, projectId, conversations, openTask, openConversation, navigate}: {
  snapshot: Snapshot; projectId: string; conversations: Conversation[];
  openTask: (id: string) => void; openConversation: (conversation: Conversation) => void; navigate: (page: string) => void;
}) {
  const pending = conversations.filter(c => c.projectId === projectId && conversationAttention(c));
  const tasks = snapshot.tasks.filter(t => t.projectId === projectId && needsAttention(t));
  return <section className="attention-list" aria-label="프로젝트에서 확인할 일">
    {!pending.length && !tasks.length && <div className="empty-state"><h2>{projectId ? "지금 확인할 일이 없습니다" : "프로젝트를 먼저 연결하세요"}</h2><p>실행 범위 승인, 최종 결과 확인, 환경·인증 문제를 여기에 모읍니다.</p><button className="button" onClick={() => navigate(projectId ? "chief" : "projects")}>{projectId ? "총괄과 다음 일 이야기하기" : "프로젝트 열기"}</button></div>}
    {pending.map(c => <article key={c.id}><div><h2>{c.messages.find(m => m.role === "user")?.text.slice(0, 100) || "총괄과 대화"}</h2><strong>{conversationStates[c.state]}</strong><p>{c.error || (c.state === "PLAN_READY" ? "실행할 범위를 확인하고 계획을 승인하세요." : c.state === "WAITING_RESULT" ? "변경 내용과 검증 근거를 확인하고 완료를 승인하세요." : "문제를 확인하고 총괄에게 해결 방향을 알려 주세요.")}</p></div><button className="button primary" onClick={() => openConversation(c)}>대화에서 확인</button></article>)}
    {tasks.map(t => <article key={t.id}><div><h2>{t.title}</h2><strong>{taskLabel(t)}</strong><p>{nextTaskAction(t)}</p>{t.message && <p>{t.message}</p>}</div><button className="button" onClick={() => openTask(t.id)}>작업 근거 확인</button></article>)}
  </section>;
}
