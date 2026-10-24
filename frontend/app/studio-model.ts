import type { Task, Snapshot } from "./types";
export const studioLabels = {
  WAITING_APPROVAL: "승인 대기",
  QUEUED: "실행 대기",
  ANALYZING: "준비 중",
  BLOCKED: "조건 부족",
  RUNNING: "작업 중",
  VERIFYING: "검증 중",
  SUCCEEDED: "작업 완료",
  FAILED: "실패",
  CANCELLED: "취소됨",
};
export const roomNames = {
  director: "CEO Office",
  planning: "Planning Room",
  development: "Development Room",
  qa: "QA Lab",
  academy: "Capability Academy",
  factory: "Workflow Factory",
  library: "Knowledge Library",
  server: "Server Room",
};
export type Room = keyof typeof roomNames;
export function taskRoom(task: Task): Room {
  if (task.status === "WAITING_APPROVAL") return "director";
  if (task.status === "VERIFYING" || task.status === "FAILED") return "qa";
  if (task.status === "BLOCKED")
    return task.requirements.some((r) => !r.available && /skill/i.test(r.name))
      ? "academy"
      : "planning";
  if (task.status === "QUEUED" || task.status === "ANALYZING")
    return "planning";
  return "development";
}
export function taskLabel(task: Task) {
  if (task.routingPending) return "모델 판단 중";
  if (task.status === "VERIFYING" && task.reviewStatus === "RUNNING") return "독립 리뷰 중";
  return task.status === "WAITING_APPROVAL"
    ? task.startedAt
      ? "결과 승인 대기"
      : "계획 승인 대기"
    : studioLabels[task.status];
}
const priority = {
  RUNNING: 0,
  VERIFYING: 1,
  ANALYZING: 2,
  WAITING_APPROVAL: 3,
  BLOCKED: 4,
  QUEUED: 5,
  FAILED: 6,
  SUCCEEDED: 7,
  CANCELLED: 8,
};
export function projectStudio(snapshot: Snapshot, projectId: string) {
  const tasks = snapshot.tasks
    .filter((t) => !projectId || t.projectId === projectId)
    .sort(
      (a, b) =>
        priority[a.status] - priority[b.status] ||
        b.updatedAt.localeCompare(a.updatedAt) ||
        a.id.localeCompare(b.id),
    );
  const employees = snapshot.agents.map((agent) => {
    const history = tasks
      .filter((t) => t.agentId === agent.id)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    const current =
      tasks.find(
        (t) =>
          t.agentId === agent.id &&
          !["SUCCEEDED", "CANCELLED", "FAILED"].includes(t.status),
      ) ||
      [...history].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
    return {
      agent,
      history,
      current,
      room: current ? taskRoom(current) : ("development" as Room),
    };
  });
  return {
    tasks,
    employees,
    runs: snapshot.workflowRuns.filter(
      (r) => !projectId || r.definition.projectId === projectId,
    ),
    skills: snapshot.skills.filter(
      (s) => !projectId || !s.projectId || s.projectId === projectId,
    ),
  };
}
