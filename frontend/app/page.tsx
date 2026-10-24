"use client";
import Academy from "./academy";
import ProjectWorkspace from "./project-workspace";
import GithubBoard from "./github-board";
import ConnectProject from "./connect-project";
import ExecutionDetails from "./execution-settings";
import SharedDocuments from "./shared-documents";
import ArchivedProjects from "./archived-projects";
import Improvements from "./improvements";
import ModelSettings from "./model-settings";
import VirtualStudio from "./virtual-studio";
import WorkflowFactory from "./workflows";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  Activity,
  ArrowUpRight,
  BookOpen,
  Box,
  Check,
  CheckCircle2,
  ChevronRight,
  Circle,
  Clock3,
  ExternalLink,
  FileCode2,
  FlaskConical,
  FolderGit2,
  GitBranch,
  Layers3,
  LayoutDashboard,
  Loader2,
  Monitor,
  Plus,
  Radio,
  RefreshCw,
  ShieldCheck,
  Square,
  Terminal,
  Users,
  X,
  XCircle,
} from "lucide-react";
import type {
  Agent,
  Project,
  Snapshot,
  Status,
  StudioEvent,
  Task,
} from "./types";
const labels: Record<Status, string> = {
  WAITING_APPROVAL: "승인 대기",
  QUEUED: "실행 대기",
  ANALYZING: "준비 중",
  BLOCKED: "조건 부족",
  RUNNING: "작업 중",
  VERIFYING: "검증 중",
  SUCCEEDED: "완료",
  FAILED: "실패",
  CANCELLED: "취소됨",
};
const active = (t: Task) =>
  ["RUNNING", "ANALYZING", "VERIFYING"].includes(t.status);
const terminal = (t: Task) =>
  ["SUCCEEDED", "FAILED", "CANCELLED"].includes(t.status);
const date = (v: string) =>
  new Date(v).toLocaleString("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
async function api<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(
    `/api${path}`,
    body === undefined
      ? { cache: "no-store" }
      : {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Studio-Client": "1",
          },
          body: JSON.stringify(body),
        },
  );
  if (!res.ok) {
    let message = "서버 연결을 확인하세요";
    try {
      const data = await res.json();
      message = data.message || message;
    } catch {}
    throw new Error(message);
  }
  return res.json();
}
function Badge({ task }: { task: Task }) {
  return (
    <span className={`badge ${task.status.toLowerCase()}`}>
      {active(task) ? (
        <Loader2 size={12} className="spin" />
      ) : (
        <span className="status-dot" />
      )}
      {labels[task.status]}
    </span>
  );
}
export default function Home() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null),
    [events, setEvents] = useState<StudioEvent[]>([]),
    [page, setPage] = useState("chief"),
    [selected, setSelected] = useState<string | null>(null),
    [modal, setModal] = useState<"project" | "task" | "agent" | null>(null),
    [error, setError] = useState(""),
    [connected, setConnected] = useState(false),
    [filter, setFilter] = useState("all");
  const [updatedAt, setUpdatedAt] = useState(0);
  const [connecting, setConnecting] = useState(false);
  const snapshotCursor = useRef(-1);
  const acceptSnapshot = useCallback((data: Snapshot) => {
    if (data.eventCursor < snapshotCursor.current) return;
    snapshotCursor.current = data.eventCursor;
    setSnapshot(data);
    setUpdatedAt(Date.now());
  }, []);
  const refresh = useCallback(async () => {
    const data = await api<Snapshot>("/snapshot");
    acceptSnapshot(data);
    const history = await api<StudioEvent[]>(
      `/events?after=${Math.max(0, data.eventCursor - 200)}`,
    );
    setEvents((old) =>
      Array.from(new Map([...old, ...history].map((e) => [e.id, e])).values())
        .sort((a, b) => a.id - b.id)
        .slice(-200),
    );
  }, [acceptSnapshot]);
  useEffect(() => {
    let closed = false,
      loading = false,
      source: EventSource | undefined;
    let refreshTimer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      if (loading || closed) return;
      loading = true;
      try {
        const data = await api<Snapshot>("/snapshot");
        if (closed) return;
        acceptSnapshot(data);
        setError("");
        const history = await api<StudioEvent[]>(
          `/events?after=${Math.max(0, data.eventCursor - 200)}`,
        );
        if (closed) return;
        setEvents(history);
        source = new EventSource(
          `/api/events/stream?after=${data.eventCursor}`,
        );
        source.onopen = () => {
          setConnected(true);
          refresh().catch(() => setConnected(false));
        };
        source.onerror = () => setConnected(false);
        source.addEventListener("studio", (event) => {
          if (closed) return;
          const item = JSON.parse((event as MessageEvent).data) as StudioEvent;
          setEvents((old) =>
            [...old.filter((e) => e.id !== item.id), item].slice(-200),
          );
          clearTimeout(refreshTimer);
          refreshTimer = setTimeout(
            () => refresh().catch(() => setConnected(false)),
            150,
          );
        });
      } catch (e) {
        if (!closed) setError((e as Error).message);
      } finally {
        loading = false;
      }
    };
    load();
    const interval = setInterval(() => {
      if (!source) void load();
      else void refresh().catch(() => setConnected(false));
    }, 5000);
    return () => {
      closed = true;
      clearTimeout(refreshTimer);
      clearInterval(interval);
      source?.close();
    };
  }, [refresh]);
  const tasks = snapshot?.tasks || [],
    projects = snapshot?.projects || [],
    agents = snapshot?.agents || [],
    task = tasks.find((t) => t.id === selected);
  const running = tasks.filter(active),
    approvals = tasks.filter((t) => t.status === "WAITING_APPROVAL"),
    failures = tasks.filter(
      (t) => t.status === "FAILED" || t.status === "BLOCKED",
    );
  const action = async (id: string, name: string) => {
    try {
      setError("");
      await api(`/tasks/${id}/${name}`, {});
      await refresh();
    } catch (e) {
      setError((e as Error).message);
      throw e;
    }
  };
  const nav = [
    { id: "chief", label: "프로젝트 공간", icon: Users },
    { id: "documents", label: "공유 문서", icon: BookOpen },
    { id: "overview", label: "스튜디오", icon: LayoutDashboard },
    { id: "improvements", label: "대표실 개선함", icon: BookOpen },
    { id: "tasks", label: "작업", icon: Layers3 },
    { id: "projects", label: "프로젝트", icon: FolderGit2 },
    { id: "agents", label: "AI 직원", icon: Users },
    { id: "workflows", label: "Workflow Factory", icon: GitBranch },
    { id: "academy", label: "Capability Academy", icon: BookOpen },
    { id: "activity", label: "활동 기록", icon: Activity },
  ];
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/">
          <span className="brand-mark">
            <Box size={22} />
          </span>
          <span>
            Studio OS<small>당신의 AI 개발 스튜디오</small>
          </span>
        </a>
        <div className="workspace">
          <span className="workspace-icon">S</span>
          <div>
            My studio<small>로컬 워크스페이스</small>
          </div>
          <ShieldCheck size={16} />
        </div>
        <nav aria-label="주요 메뉴">
          {nav.filter(n=>["chief","documents","overview","projects"].includes(n.id)).map((n) => (
            <button
              key={n.id}
              className={page === n.id ? "nav-item selected" : "nav-item"}
              onClick={() => {
                setPage(n.id);
                setSelected(null);
              }}
            >
              <n.icon size={18} />
              {n.label}
              {n.id === "tasks" && tasks.length > 0 && (
                <span className="nav-count">{tasks.length}</span>
              )}
            </button>
          ))}
          <details className="advanced-nav"><summary>운영 상세</summary>{nav.filter(n=>!["chief","documents","overview","projects"].includes(n.id)).map(n=><button key={n.id} className={page===n.id?"nav-item selected":"nav-item"} onClick={()=>{setPage(n.id);setSelected(null);}}><n.icon size={18}/>{n.label}</button>)}</details>
        </nav>
        <div className="sidebar-bottom">
          <span className="local-label">
            <span className={`dot ${connected ? "green" : ""}`} />
            {connected ? "이벤트 연결됨" : "이벤트 재연결 중"}
          </span>
          <p>
            Core Runtime <span>v0.1</span>
          </p>
          <small>코드는 로컬에. 결정은 당신에게.</small>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div>
            My studio <ChevronRight size={14} />
            <strong>{nav.find((n) => n.id === page)?.label}</strong>
          </div>
          <span className="local-pill">
            <Monitor size={14} />
            로컬 실행
          </span>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <h1>
                {
                  {
                    chief: "프로젝트 공간",
                    documents: "함께 보는 문서",
                    overview: "오늘의 스튜디오",
                    improvements: "대표실 개선함",
                    tasks: "작업 보드",
                    projects: "프로젝트",
                    agents: "AI 직원",
                    workflows: "Workflow Factory",
                    academy: "Capability Academy",
                    activity: "스튜디오 활동",
                  }[page]
                }
              </h1>
              <p>
                {
                  {
                    overview:
                      "AI 직원의 작업부터 마지막 승인까지, 한곳에서 관리하세요.",
                    tasks: "지시하고, 검증하고, 결과를 승인하세요.",
                    projects:
                      "로컬 Git 저장소와 프로젝트의 작업 규칙을 관리하세요.",
                    agents: "역할과 실행 도구를 연결해 팀을 구성하세요.",
                    workflows:
                      "단계를 연결하고, 검증하고, 실패한 시도를 이어가세요.",
                    academy: "Skill을 준비하고, 검증하고, 작업을 이어가세요.",
                    activity:
                      "실제 실행에서 발생한 이벤트가 순서대로 기록됩니다.",
                  }[page]
                }
              </p>
            </div>
            {!["chief", "documents", "academy"].includes(page) && <button
              className="button primary"
              onClick={() =>
                setModal(
                  page === "projects"
                    ? "project"
                    : page === "agents"
                      ? "agent"
                      : "task",
                )
              }
            >
              <Plus size={17} />
              {page === "projects"
                ? "프로젝트 등록"
                : page === "agents"
                  ? "직원 등록"
                  : "새 작업"}
            </button>}
          </div>
          {error && (
            <div className="error-banner" role="alert">
              <XCircle size={18} />
              <span>{error}</span>
              <button aria-label="오류 닫기" onClick={() => setError("")}>
                <X size={16} />
              </button>
            </div>
          )}
          {!snapshot ? (
            <div className="empty-state">
              <Loader2 className="spin" />
              <h2>스튜디오에 연결 중입니다</h2>
              <p>백엔드를 실행한 뒤 연결을 다시 확인하세요.</p>
              <button
                className="button"
                onClick={() => refresh().catch((e) => setError(e.message))}
              >
                다시 연결
              </button>
            </div>
          ) : (
            <>
              {page === "chief" && <ProjectWorkspace snapshot={snapshot} events={events} connected={connected} refresh={refresh} addProject={()=>setConnecting(true)}/>}
              {page === "documents" && <SharedDocuments snapshot={snapshot}/>}
              {(page === "overview" || page === "tasks") && (
                <>
                  <div className="status-strip">
                    {[
                      {
                        name: "진행 중",
                        count: running.length,
                        icon: Radio,
                        detail: "에이전트가 작업하고 있어요",
                        filter: "running",
                      },
                      {
                        name: "승인 대기",
                        count: approvals.length,
                        icon: ShieldCheck,
                        detail: "디렉터의 검토가 필요해요",
                        filter: "approval",
                      },
                      {
                        name: "확인 필요",
                        count: failures.length,
                        icon: FlaskConical,
                        detail: "실패 또는 실행 조건 부족",
                        filter: "attention",
                      },
                    ].map((s) => (
                      <button
                        key={s.name}
                        className={`status-summary ${filter === s.filter ? "focused" : ""}`}
                        onClick={() =>
                          setFilter(filter === s.filter ? "all" : s.filter)
                        }
                      >
                        <span className={`summary-icon ${s.filter}`}>
                          <s.icon size={20} />
                        </span>
                        <span>
                          <span className="summary-name">{s.name}</span>
                          <strong>
                            {s.count}
                            <small>개 작업</small>
                          </strong>
                        </span>
                        <span className="summary-detail">{s.detail}</span>
                      </button>
                    ))}
                  </div>
                  {page === "overview" && snapshot && (
                    <VirtualStudio
                      snapshot={snapshot}
                      events={events}
                      connected={connected}
                      updatedAt={updatedAt}
                      openTask={setSelected}
                      navigate={setPage}
                    />
                  )}
                  <div className="section-title">
                    <h2>
                      {page === "overview" ? "작업 현황" : "모든 작업"}
                      <span>{tasks.length}</span>
                    </h2>
                    <div className="filter-tabs">
                      {[
                        { value: "all", name: "전체" },
                        { value: "running", name: "진행 중" },
                        { value: "approval", name: "승인 대기" },
                        { value: "attention", name: "확인 필요" },
                      ].map((f) => (
                        <button
                          key={f.value}
                          className={filter === f.value ? "on" : ""}
                          onClick={() => setFilter(f.value)}
                        >
                          {f.name}
                        </button>
                      ))}
                    </div>
                  </div>
                  {tasks.length === 0 ? (
                    <div className="onboarding">
                      <span className="onboarding-icon">
                        <FolderGit2 size={28} />
                      </span>
                      <div>
                        <h2>첫 번째 프로젝트로 시작하세요</h2>
                        <p>
                          Git 저장소를 등록하고 AI 직원에게 첫 작업을
                          맡겨보세요.
                          <br />
                          AI 연결 없이 실행 흐름을 확인하는 데모 직원도 준비되어
                          있어요.
                        </p>
                        <button
                          className="button"
                          onClick={() =>
                            setModal(projects.length ? "task" : "project")
                          }
                        >
                          <Plus size={15} />
                          {projects.length ? "첫 작업 만들기" : "프로젝트 등록"}
                        </button>
                      </div>
                      <div className="onboarding-steps">
                        <span className={projects.length ? "done" : ""}>
                          <CheckCircle2 size={16} />
                          프로젝트 등록
                        </span>
                        <span>
                          <Circle size={16} />
                          작업 지시와 실행 승인
                        </span>
                        <span>
                          <Circle size={16} />
                          변경 사항 검토
                        </span>
                      </div>
                    </div>
                  ) : (
                    <div className="task-table">
                      <div className="table-head">
                        <span>작업 / 프로젝트</span>
                        <span>담당 직원</span>
                        <span>상태</span>
                        <span>생성 시각</span>
                      </div>
                      {tasks
                        .filter(
                          (t) =>
                            filter === "all" ||
                            (filter === "running" && active(t)) ||
                            (filter === "approval" &&
                              t.status === "WAITING_APPROVAL") ||
                            (filter === "attention" &&
                              ["FAILED", "BLOCKED"].includes(t.status)),
                        )
                        .map((t) => (
                          <button
                            key={t.id}
                            className={`task-row ${selected === t.id ? "chosen" : ""}`}
                            onClick={() => setSelected(t.id)}
                          >
                            <span className="task-name">
                              <span className="task-symbol">
                                <FileCode2 size={18} />
                              </span>
                              <span>
                                <strong>{t.title}</strong>
                                <small>
                                  {
                                    projects.find((p) => p.id === t.projectId)
                                      ?.name
                                  }
                                  {t.adapter === "demo" && <em>데모</em>}
                                </small>
                              </span>
                            </span>
                            <span className="assignee">
                              <span className={`avatar ${t.adapter}`}>
                                {agents
                                  .find((a) => a.id === t.agentId)
                                  ?.name.charAt(0)}
                              </span>
                              {agents.find((a) => a.id === t.agentId)?.name}
                            </span>
                            <span>
                              <Badge task={t} />
                            </span>
                            <span className="row-date">
                              {date(t.createdAt)}
                              <ChevronRight size={15} />
                            </span>
                          </button>
                        ))}
                    </div>
                  )}
                </>
              )}
              {page === "projects" && (
                <div className="project-grid">
                  {projects.map((p) => (
                    <article className="project-card" key={p.id}>
                      <FolderGit2 size={25} />
                      <h2>{p.name}</h2>
                      <button className="button primary" onClick={()=>{localStorage.setItem("studio.workspace.project",p.id);setPage("chief");}}>이 프로젝트에서 대화하기</button>
                      <details><summary>연결된 저장소</summary><code>{p.path}</code></details>
                      <button className="button" onClick={async()=>{try {await api(`/projects/${p.id}/archive`,{archived:true});await refresh();}catch(e){setError((e as Error).message);}}}>목록에서 숨기기</button>
                      <small>저장소 파일은 유지됩니다.</small>
                      <div>
                        <span>
                          {tasks.filter((t) => t.projectId === p.id).length}개
                          작업
                        </span>
                        <span>
                          {p.testCommand.length
                            ? "테스트 설정됨"
                            : "테스트 미설정"}
                        </span>
                      </div>
                      <details>
                        <summary>프로젝트 규칙과 테스트 수정</summary>
                        <ProjectSettings
                          project={p}
                          refresh={refresh}
                          fail={setError}
                        />
                      </details>
                    </article>
                  ))}
                  <button
                    className="add-card"
                    onClick={() => setModal("project")}
                  >
                    <Plus size={24} />
                    로컬 Git 저장소 등록
                  </button>
                </div>
              )}
              {page === "projects" && projects.map(p=><GithubBoard key={p.id} projectId={p.id}/>)}
              {page === "projects" && <ArchivedProjects refresh={refresh}/>}
              {page === "agents" && (
                <div className="agent-grid">
                  {agents.map((a) => {
                    const work = tasks.find(
                      (t) => t.agentId === a.id && active(t),
                    );
                    const available = snapshot.adapters
                      .find((d) => d.type === a.adapter)
                      ?.requirements.every((r) => r.available);
                    return (
                      <article className="agent-card" key={a.id}>
                        <span className={`avatar large ${a.adapter}`}>
                          {a.name.charAt(0)}
                        </span>
                        <h2>{a.name}</h2>
                        <span className="role-label">{a.role}</span>
                        <p>
                          {a.adapter === "demo"
                            ? "고정 샘플 생성 · AI 호출 없음"
                            : "Codex CLI · 로컬 로그인 사용"}
                        </p>
                        <div className="agent-bottom">
                          <span
                            className={`dot ${work ? "blue" : available ? "green" : "amber"}`}
                          />
                          {work
                            ? work.title
                            : available
                              ? "대기 중"
                              : "CLI 연결 필요"}
                        </div>
                      </article>
                    );
                  })}
                </div>
              )}
              {page === "workflows" && (
                <WorkflowFactory
                  snapshot={snapshot}
                  refresh={refresh}
                  openTask={setSelected}
                  openAcademy={() => setPage("academy")}
                />
              )}
              {page === "improvements" && (
                <Improvements
                  snapshot={snapshot}
                  refresh={refresh}
                  openTask={setSelected}
                  navigate={setPage}
                />
              )}
              {page === "academy" && (
                <Academy
                  snapshot={snapshot}
                  refresh={refresh}
                  openTask={setSelected}
                />
              )}
              {(page === "activity" || page === "overview") && (
                <section className="activity-section">
                  <div className="section-title">
                    <h2>최근 활동</h2>
                    <span className="subtle">실행 이벤트</span>
                  </div>
                  {events.length === 0 ? (
                    <p className="empty-note">
                      첫 프로젝트를 등록하면 활동이 여기에 기록됩니다.
                    </p>
                  ) : (
                    events
                      .slice()
                      .reverse()
                      .slice(0, page === "activity" ? 100 : 5)
                      .map((e) => (
                        <button
                          key={e.id}
                          className="event-row"
                          disabled={!e.taskId}
                          onClick={() => e.taskId && setSelected(e.taskId)}
                        >
                          <span
                            className={`event-dot ${e.type.includes("failed") ? "red" : ""}`}
                          />
                          <span>
                            <strong>
                              {e.data.message || e.data.name || e.type}
                            </strong>
                            <small>
                              {e.taskId
                                ? tasks.find((t) => t.id === e.taskId)?.title
                                : "스튜디오"}
                            </small>
                          </span>
                          <span className="event-type">{e.type}</span>
                          <time>{date(e.createdAt)}</time>
                        </button>
                      ))
                  )}
                </section>
              )}
            </>
          )}
        </main>
        <footer>
          Studio OS <span>로컬 우선 실행 · 모든 변경은 검토 후 승인</span>
          <a
            href="https://github.com/openai/codex"
            target="_blank"
            rel="noreferrer"
          >
            Codex CLI <ExternalLink size={12} />
          </a>
        </footer>
      </div>
      {task && (
        <TaskPanel
          refresh={refresh}
          task={task}
          project={projects.find((p) => p.id === task.projectId)}
          agent={agents.find((a) => a.id === task.agentId)}
          close={() => setSelected(null)}
          action={action}
        />
      )}
      {connecting && <ConnectProject close={()=>setConnecting(false)} saved={refresh}/>}
      {modal && (
        <CreateModal
          kind={modal}
          projects={projects}
          agents={agents}
          close={() => setModal(null)}
          saved={async (id) => {
            await refresh();
            setModal(null);
            if (id) setSelected(id);
          }}
        />
      )}
    </div>
  );
}
function TaskPanel({
  refresh,
  task: t,
  project,
  agent,
  close,
  action,
}: {
  refresh: () => Promise<void>;
  task: Task;
  project?: Project;
  agent?: Agent;
  close: () => void;
  action: (id: string, name: string) => Promise<void>;
}) {
  const [tab, setTab] = useState("plan"),
    [logs, setLogs] = useState<Record<string, string>>({}),
    [diff, setDiff] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [close]);
  useEffect(() => {
    let closed = false;
    const load = async () => {
      try {
        if (tab === "logs") {
          const data = await api<Record<string, string>>(`/tasks/${t.id}/logs`);
          if (!closed) setLogs(data);
        }
        if (tab === "diff") {
          const data = await api<{ diff: string }>(`/tasks/${t.id}/changes`);
          if (!closed) setDiff(data.diff);
        }
      } catch (e) {
        if (!closed) setError((e as Error).message);
      }
    };
    load();
    const interval = setInterval(load, 2000);
    return () => {
      closed = true;
      clearInterval(interval);
    };
  }, [t.id, tab]);
  const doAction = async (name: string) => {
    setBusy(true);
    setError("");
    try {
      await action(t.id, name);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="panel-overlay">
      <button
        className="overlay-dismiss"
        aria-label="작업 상세 닫기"
        onClick={close}
      />
      <aside
        className="task-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="task-panel-title"
      >
        <div className="panel-top">
          <span>작업 상세</span>
          <button
            autoFocus
            className="icon-button"
            aria-label="닫기"
            onClick={close}
          >
            <X size={20} />
          </button>
        </div>
        <div className="panel-body">
          <Badge task={t} />
          <h2 id="task-panel-title">{t.title}</h2>
          <p className="subtle">
            {project?.name} / {agent?.name}
          </p>
          <div className="task-message">{t.message}</div>
          <ExecutionDetails label="작업 실행에 전달한 설정" settings={t.execution}/>
          {t.routingPending && <ExecutionDetails label="작업 모델" pending/>}
          {t.model && !t.routingPending && (
            <div className="improvement-routing">
              <strong>
                선택 계획 · 실행 도구 {t.adapter} · 모델 {t.model} · 추론{" "}
                {t.reasoning}
              </strong>
              <p>{t.routingReason}</p>
              <small>
                이 실행 계획에 고정된 선택입니다. 실패 시 다른 모델로 자동
                재호출하지 않습니다.
              </small>
            </div>
          )}
          {!t.startedAt &&
            !t.approvedAt &&
            ["WAITING_APPROVAL", "BLOCKED"].includes(t.status) &&
            t.adapter !== "demo" && (
              <ModelSettings task={t} refresh={refresh} />
            )}
          {t.improvementAnalysis && (
            <details open>
              <summary>AI 개선 후보 · 코드 변경 없는 분석</summary>
              {t.improvementProposals?.length ? (
                <pre className="proposal-output">
                  {JSON.stringify(t.improvementProposals, null, 2)}
                </pre>
              ) : (
                <p>분석 승인 후 후보가 생성됩니다.</p>
              )}
            </details>
          )}
          <div className="execution-steps">
            {["실행 승인", "에이전트 작업", "검증", "결과 승인"].map((s, i) => (
              <span
                key={s}
                className={
                  (i === 0 && !!t.approvedAt) ||
                  (i === 1 && t.agentExitCode === 0) ||
                  (i === 2 && t.verification === "PASSED") ||
                  (i === 3 && t.status === "SUCCEEDED")
                    ? "complete"
                    : ""
                }
              >
                <CheckCircle2 size={16} />
                {s}
              </span>
            ))}
          </div>
          <div className="panel-tabs">
            {[
              { id: "plan", name: "실행 계획" },
              { id: "logs", name: "로그" },
              { id: "diff", name: "코드 변경" },
              { id: "artifacts", name: "결과물" },
            ].map((x) => (
              <button
                key={x.id}
                className={tab === x.id ? "on" : ""}
                onClick={() => setTab(x.id)}
              >
                {x.name}
              </button>
            ))}
          </div>
          {error && <p role="alert">{error}</p>}
          {t.routingPending && <p role="status">모델 자동 판단 중 · 완료 후 계획을 승인할 수 있습니다.</p>}
          {t.failure && <div className="routing-summary"><strong>실패 원인: {t.failure.category}</strong><p>{t.failure.reason}</p></div>}
          {t.requireReview && <div className="routing-summary"><strong>독립 리뷰 · {t.reviewStatus || "NOT_RUN"}</strong>
            <p>{t.reviewerProvider ? `${t.reviewerProvider} / ${t.reviewerModel} / ${t.reviewerReasoning}` : "검증 후 별도 읽기 전용 호출 · 다른 공급자가 준비돼 있으면 우선 선택"}</p>
            {t.review && <><p>{t.review.summary}</p><ul>{t.review.findings.map((f, i) => <li key={i}>{f.severity} · {f.file}: {f.detail}</li>)}</ul></>}
          </div>}
          {!t.workflowRunId && !t.improvementAnalysis && ["FAILED", "CANCELLED"].includes(t.status) && (t.attempt || 1) < 3 &&
            <button className="button" disabled={busy} onClick={async () => {
              setBusy(true); setError("");
              try { await api(`/tasks/${t.id}/retry`, {correction: ""}); await refresh(); close(); }
              catch (e) { setError((e as Error).message); } finally { setBusy(false); }
            }}>{t.retryTaskId ? "재시도 계획 확인 (작업 목록)" : "실패 원인에 맞춰 재시도 계획 만들기"}</button>}
          {tab === "plan" && (
            <>
              <h3>작업 지시</h3>
              <p className="prompt-text">{t.prompt}</p>
              <dl className="task-meta">
                <dt>실행 도구</dt>
                <dd>
                  {t.adapter === "demo"
                    ? "내장 데모 (AI 호출 없음)"
                    : `${t.adapter} CLI`}
                </dd>
                <dt>역할</dt>
                <dd>{t.role}</dd>
                <dt>시간 제한</dt>
                <dd>{t.timeoutSeconds}초 · 최대 동시 실행 1개</dd>
                <dt>기준 commit</dt>
                <dd>
                  <code>{t.baseCommit.slice(0, 12)}</code>
                </dd>
                <dt>작업 브랜치</dt>
                <dd>
                  <code>{t.branch}</code>
                </dd>
                <dt>작업 폴더</dt>
                <dd>
                  <code>{t.worktree}</code>
                </dd>
                <dt>검증 명령</dt>
                <dd>
                  <code>
                    {t.testCommand.length
                      ? JSON.stringify(t.testCommand)
                      : "미설정 · 자동 검증 미확인"}
                  </code>
                </dd>
                <dt>검증 결과</dt>
                <dd>
                  {
                    { NOT_RUN: "미확인", PASSED: "통과", FAILED: "실패" }[
                      t.verification
                    ]
                  }
                </dd>
              </dl>
              {t.workflowRunId && (
                <>
                  <h3>Workflow 연결</h3>
                  <p className="prompt-text">
                    단계 {t.workflowNodeId} · 시도 {t.workflowAttempt}
                    <br />
                    {t.requireVerification
                      ? "테스트 통과 필수"
                      : "미확인 결과의 수동 승인 허용"}
                  </p>
                  <p className="subtle">
                    선행 변경:{" "}
                    {t.workflowInputs
                      .map((i) => `${i.nodeId} (${i.digest.slice(0, 10)})`)
                      .join(", ") || "시작 단계"}
                  </p>
                </>
              )}
              <h3>고정된 Skill 버전</h3>
              {(t.skills || []).length ? (
                (t.skills || []).map((s) => (
                  <p key={s.id} className="prompt-text">
                    <strong>
                      {s.name} @ {s.version}
                    </strong>
                    <br />
                    <code>{s.digest.slice(0, 16)}</code>
                  </p>
                ))
              ) : (
                <p className="subtle">
                  {(t.requiredSkills || []).length
                    ? "필요한 Skill을 활성화하고 재검사하세요"
                    : "별도 Skill 지정 없음"}
                </p>
              )}
              <h3>실행 조건</h3>
              {t.requirements.map((r) => (
                <div
                  className={`requirement ${r.available ? "" : "missing"}`}
                  key={r.name}
                >
                  {r.available ? (
                    <CheckCircle2 size={17} />
                  ) : (
                    <XCircle size={17} />
                  )}
                  <span>
                    <strong>{r.name}</strong>
                    <small>{r.detail}</small>
                  </span>
                </div>
              ))}
              <h3>실행 권한과 변경 범위</h3>
              <p className="policy-note">
                승인하면 새 worktree와 브랜치를 만들고 등록된 도구를 실행합니다.
                Codex는 workspace-write sandbox를 사용하며 명령의 네트워크
                접근과 추가 권한 요청을 차단합니다. 테스트는 OS sandbox에서
                실행합니다. 로컬 파일 읽기가 허용되므로 신뢰하는 저장소에
                사용하세요.{" "}
                {t.adapter === "codex" &&
                  "AI 서비스 통신에는 기존 CLI 로그인을 사용하며 공급자 사용량이 발생할 수 있습니다."}
              </p>
              <h3>프로젝트 규칙</h3>
              <p className="prompt-text subtle">
                {t.rules || "등록된 규칙 없음"}
              </p>
            </>
          )}
          {tab === "logs" && (
            <>
              <p className="subtle">
                최근 64 KiB · 2초마다 갱신 · 전체 로그는 결과물에서 다운로드
              </p>
              {Object.entries(logs).length ? (
                Object.entries(logs).map(([name, text]) => (
                  <div key={name}>
                    <h3>{name}</h3>
                    <pre className="log-output">{text}</pre>
                  </div>
                ))
              ) : (
                <div className="empty-note">
                  실행이 시작되면 로그가 여기에 표시됩니다.
                </div>
              )}
            </>
          )}
          {tab === "diff" && (
            <>
              <p className="subtle">
                기준 commit 이후 변경 사항과 새 파일을 표시합니다.
              </p>
              <pre className="diff-output">
                {diff || "아직 코드 변경이 없습니다."}
              </pre>
            </>
          )}
          {tab === "artifacts" && (
            <>
              <p className="subtle">
                실행 기록과 변경 사항은 worktree 정리 후에도 보존됩니다.
              </p>
              {[
                "changes.patch",
                "files.txt",
                "execution.json",
                "skills.json",
                ...(t.review ? ["review.json", "review.log"] : []),
                ...(t.workflowRunId ? ["step.patch", "workflow.json"] : []),
                "agent.jsonl",
                ...(t.testCommand.length ? ["tests.log"] : []),
                ...(t.status === "FAILED" ? ["worker.log"] : []),
              ].map((name) => (
                <a
                  className="artifact-link"
                  href={`/api/tasks/${t.id}/artifacts/${name}`}
                  download
                  key={name}
                >
                  <FileCode2 size={17} />
                  {name}
                  <ArrowUpRight size={16} />
                </a>
              ))}
              <p className="empty-note">
                해당 단계가 실행되지 않은 파일은 제공되지 않습니다. patch는 원본
                저장소에서 git apply로 검토·적용할 수 있습니다.
              </p>
            </>
          )}
        </div>
        <div className="panel-actions">
          {t.status === "WAITING_APPROVAL" && (
            <button
              disabled={busy}
              className="button primary"
              onClick={() => doAction("approve")}
            >
              <ShieldCheck size={17} />
              {t.agentExitCode === null
                ? "계획 승인 및 실행"
                : "결과 검토 완료"}
            </button>
          )}
          {t.status === "BLOCKED" && (
            <button
              disabled={busy}
              className="button primary"
              onClick={() => doAction("recheck")}
            >
              <RefreshCw size={16} />
              실행 조건 다시 확인
            </button>
          )}
          {!terminal(t) && (
            <button
              className="button"
              disabled={busy}
              onClick={() => doAction("cancel")}
            >
              <Square size={14} />
              {t.status === "WAITING_APPROVAL" ? "거절 및 취소" : "작업 취소"}
            </button>
          )}
          {terminal(t) && !t.cleaned && (
            <button
              className="button"
              disabled={busy}
              onClick={() => doAction("cleanup")}
            >
              빈 worktree 정리
            </button>
          )}
          {t.cleaned && <span className="subtle">작업 폴더 정리됨</span>}
        </div>
      </aside>
    </div>
  );
}
function CreateModal({
  kind,
  projects,
  agents,
  close,
  saved,
}: {
  kind: "project" | "agent" | "task";
  projects: Project[];
  agents: Agent[];
  close: () => void;
  saved: (id?: string) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) close();
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [close, busy]);
  return (
    <div className="modal-overlay">
      <section
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
      >
        <div className="modal-header">
          <h2 id="modal-title">
            {kind === "project"
              ? "프로젝트 등록"
              : kind === "agent"
                ? "AI 직원 등록"
                : "새 작업 만들기"}
          </h2>
          <button
            className="icon-button"
            aria-label="닫기"
            onClick={close}
            disabled={busy}
          >
            <X size={20} />
          </button>
        </div>
        <form
          ref={form}
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError("");
            try {
              const data = Object.fromEntries(new FormData(e.currentTarget));
              let body: unknown = data;
              if (kind === "project")
                body = {
                  ...data,
                  testCommand: JSON.parse(String(data.testCommand || "[]")),
                };
              if (kind === "task")
                body = {
                  ...data,
                  timeoutSeconds: Number(data.timeoutSeconds),
                  requiredSkills: String(data.requiredSkills || "")
                    .split(/[,\n]/)
                    .map((s) => s.trim())
                    .filter(Boolean),
                };
              const result = await api<{ id: string }>(
                `/${kind === "project" ? "projects" : kind === "agent" ? "agents" : "tasks"}`,
                body,
              );
              await saved(kind === "task" ? result.id : undefined);
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          {kind === "project" && (
            <>
              <label>
                프로젝트 이름
                <input
                  name="name"
                  required
                  maxLength={120}
                  placeholder="나의 게임 프로젝트"
                  autoFocus
                />
              </label>
              <label>
                로컬 Git 저장소 경로
                <input
                  name="path"
                  required
                  placeholder="/Users/me/projects/my-game"
                />
                <small>첫 commit이 있는 저장소의 최상위 절대 경로</small>
              </label>
              <label>
                프로젝트 규칙
                <textarea
                  name="rules"
                  rows={3}
                  placeholder="TypeScript strict 사용. 기존 테스트를 유지하고 변경 이유를 설명할 것."
                />
              </label>
              <label>
                테스트 명령 (인자 배열)
                <input
                  name="testCommand"
                  defaultValue="[]"
                  spellCheck={false}
                />
                <small>
                  예: ["npm", "test", "--", "--runInBand"] · []이면 미확인으로
                  표시
                </small>
              </label>
            </>
          )}
          {kind === "agent" && (
            <>
              <label>
                직원 이름
                <input
                  name="name"
                  required
                  maxLength={80}
                  autoFocus
                  placeholder="Client developer"
                />
              </label>
              <label>
                역할
                <input
                  name="role"
                  required
                  maxLength={80}
                  defaultValue="Developer"
                />
              </label>
              <label>
                실행 도구
                <select name="adapter">
                  <option value="codex">Codex CLI</option>
                  <option value="demo">데모 · 고정 샘플 생성</option>
                </select>
              </label>
              <p className="form-note">
                Claude 어댑터는 후속 단계에서 추가할 수 있습니다. Codex는
                호스트의 CLI 설치와 로그인이 필요합니다.
              </p>
            </>
          )}
          {kind === "task" && (
            <>
              <label>
                작업 제목
                <input
                  name="title"
                  required
                  maxLength={160}
                  autoFocus
                  placeholder="적 사망 시 아이템 드롭 구현"
                />
              </label>
              <div className="form-row">
                <label>
                  프로젝트
                  <select
                    name="projectId"
                    required
                    defaultValue={projects[0]?.id || ""}
                  >
                    <option value="" disabled>
                      프로젝트 선택
                    </option>
                    {projects.map((p) => (
                      <option value={p.id} key={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  담당 직원
                  <select
                    name="agentId"
                    required
                    defaultValue="auto"
                  >
                    <option value="auto">Auto · 모델·추론 자동 판단 + 독립 리뷰</option>
                    {agents.map((a) => (
                      <option value={a.id} key={a.id}>
                        {a.name}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              {projects.length === 0 && (
                <p className="form-error">
                  프로젝트 메뉴에서 Git 저장소를 먼저 등록하세요.
                </p>
              )}
              <label>
                필요한 Skill 이름
                <input
                  name="requiredSkills"
                  placeholder="readme-guide, code-review"
                />
                <small>
                  쉼표로 구분. 없거나 비활성인 Skill은 작업을 차단하며
                  Academy에서 해결할 수 있습니다.
                </small>
              </label>
              <label>
                작업 지시
                <textarea
                  name="prompt"
                  rows={5}
                  required
                  maxLength={24000}
                  placeholder="무엇을 만들고, 어떤 조건을 만족해야 하는지 구체적으로 적어주세요."
                />
              </label>
              <label>
                실행 시간 제한 (초)
                <input
                  name="timeoutSeconds"
                  type="number"
                  min={10}
                  max={3600}
                  defaultValue={600}
                  required
                />
              </label>
              <p className="form-note">
                <ShieldCheck size={16} />
                작업 생성 후 실행 계획을 검토하고 승인합니다. 등록된 테스트
                명령과 프로젝트 규칙은 생성 시점에 고정됩니다.
              </p>
            </>
          )}
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <div className="modal-actions">
            <button
              type="button"
              className="button"
              onClick={close}
              disabled={busy}
            >
              취소
            </button>
            <button
              className="button primary"
              disabled={busy || (kind === "task" && !projects.length)}
            >
              {busy ? (
                <Loader2 size={16} className="spin" />
              ) : (
                <Plus size={16} />
              )}
              {kind === "task" ? "작업 생성" : "등록"}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
function ProjectSettings({
  project,
  refresh,
  fail,
}: {
  project: Project;
  refresh: () => Promise<void>;
  fail: (error: string) => void;
}) {
  const [busy, setBusy] = useState(false),
    [saved, setSaved] = useState(false);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setSaved(false);
        try {
          const data = Object.fromEntries(new FormData(e.currentTarget));
          await api(`/projects/${project.id}/settings`, {
            rules: data.rules,
            testCommand: JSON.parse(String(data.testCommand)),
          });
          await refresh();
          setSaved(true);
        } catch (e) {
          fail((e as Error).message);
        } finally {
          setBusy(false);
        }
      }}
    >
      <label>
        프로젝트 규칙
        <textarea name="rules" defaultValue={project.rules} rows={4} />
      </label>
      <label>
        테스트 명령
        <input
          name="testCommand"
          defaultValue={JSON.stringify(project.testCommand)}
        />
      </label>
      <p className="subtle">저장 후 생성하는 작업부터 적용됩니다.</p>
      <button className="button" disabled={busy}>
        {saved ? "저장됨" : "설정 저장"}
      </button>
    </form>
  );
}
