"use client";

import { useRef, useState } from "react";
import {
  GitBranch,
  Plus,
  Play,
  Pencil,
  ArrowRight,
  CheckCircle2,
  RotateCcw,
  Square,
  X,
} from "lucide-react";
import type {
  Snapshot,
  WorkflowDefinition,
  WorkflowNode,
  WorkflowRun,
} from "./types";

const labels: Record<string, string> = {
  WAITING_DEPENDENCIES: "선행 단계 대기",
  WAITING_APPROVAL: "승인 대기",
  QUEUED: "실행 대기",
  ANALYZING: "준비 중",
  BLOCKED: "역량 부족",
  RUNNING: "실행 중",
  VERIFYING: "검증 중",
  SUCCEEDED: "완료",
  FAILED: "실패",
  CANCELLED: "취소됨",
};
async function api<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Studio-Client": "1" },
    body: JSON.stringify(body),
  });
  const data = await response.json();
  if (!response.ok)
    throw new Error(data.message || "요청을 완료하지 못했습니다");
  return data;
}
const date = (value: string) =>
  new Date(value).toLocaleString("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
type DraftNode = WorkflowNode & { testText: string; skillsText: string };
const draftNode = (n: WorkflowNode): DraftNode => ({
  ...n,
  testText: n.testCommand === null ? "" : JSON.stringify(n.testCommand),
  skillsText: n.requiredSkills.join(", "),
});

export default function WorkflowFactory({
  snapshot,
  refresh,
  openTask,
  openAcademy,
}: {
  snapshot: Snapshot;
  refresh: () => Promise<void>;
  openTask: (id: string) => void;
  openAcademy: () => void;
}) {
  const [editing, setEditing] = useState<WorkflowDefinition | "new" | null>(
    null,
  );
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [corrections, setCorrections] = useState<Record<string, string>>({});
  const requestKeys = useRef<Record<string, string>>({});
  const definitions = snapshot.workflows || [];
  const runs = [...(snapshot.workflowRuns || [])].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  );
  const run = runs.find((r) => r.id === selected) || runs[0];
  async function action(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await fn();
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="workflow-factory">
      <div className="notice">
        <GitBranch size={24} />
        <div>
          <h2>단계의 결과를 다음 작업으로 연결하세요</h2>
          <p>
            선행 결과 승인 → 변경 사항 전달 → 단계 실행 → 검증 → 결과 승인.
            실패하면 기록을 보존한 새 시도로 이어갑니다.
          </p>
        </div>
      </div>
      <div className="workflow-summary">
        <div>
          <strong>{definitions.length}</strong>
          <span>저장된 Workflow</span>
        </div>
        <div>
          <strong>
            {
              runs.filter(
                (r) => !["SUCCEEDED", "CANCELLED", "FAILED"].includes(r.status),
              ).length
            }
          </strong>
          <span>진행 중 실행</span>
        </div>
        <div>
          <strong>{runs.filter((r) => r.status === "SUCCEEDED").length}</strong>
          <span>완료된 실행</span>
        </div>
      </div>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="academy-notice">
          {notice}
        </p>
      )}
      <section className="workflow-section">
        <div className="section-title">
          <h2>Workflow 정의</h2>
          <button
            className="button primary"
            disabled={busy}
            onClick={() => setEditing("new")}
          >
            <Plus size={16} />
            Workflow 만들기
          </button>
        </div>
        {!definitions.length && (
          <p className="empty-note">
            단계를 추가하고 필요한 선행 단계를 연결해 첫 Workflow를 저장하세요.
          </p>
        )}
        <div className="workflow-definition-grid">
          {definitions.map((d) => (
            <article key={d.id} className="workflow-definition">
              <div className="section-title">
                <h3>{d.name}</h3>
                <span className="subtle">v{d.revision}</span>
              </div>
              <p className="subtle">
                {snapshot.projects.find((p) => p.id === d.projectId)?.name} ·{" "}
                {d.nodes.length}단계
              </p>
              <div className="workflow-chain">
                {d.nodes.map((n) => (
                  <span key={n.id} title={n.dependsOn.join(", ")}>
                    {n.dependsOn.length > 0 && <ArrowRight size={12} />}{" "}
                    {n.title}
                  </span>
                ))}
              </div>
              <div className="workflow-actions">
                <button
                  className="button"
                  disabled={busy}
                  onClick={() => setEditing(d)}
                >
                  <Pencil size={14} />
                  정의 편집
                </button>
                <button
                  className="button primary"
                  disabled={busy}
                  onClick={() =>
                    action(async () => {
                      const key = (requestKeys.current[d.id] ||=
                        crypto.randomUUID());
                      const next = await api<WorkflowRun>(
                        `/workflows/${d.id}/runs`,
                        { requestKey: key },
                      );
                      delete requestKeys.current[d.id];
                      setSelected(next.id);
                      setNotice(
                        "실행을 만들었습니다. 준비된 단계의 계획을 검토하고 승인하세요.",
                      );
                    })
                  }
                >
                  <Play size={14} />
                  실행 준비
                </button>
              </div>
            </article>
          ))}
        </div>
      </section>
      {editing && (
        <WorkflowEditor
          key={editing === "new" ? "new" : `${editing.id}-${editing.revision}`}
          value={editing === "new" ? null : editing}
          snapshot={snapshot}
          close={() => setEditing(null)}
          save={async (body) => {
            const value = await api<WorkflowDefinition>(
              editing === "new" ? "/workflows" : `/workflows/${editing.id}`,
              body,
            );
            await refresh();
            setEditing(null);
            setNotice(
              `${value.name} v${value.revision} 저장 완료. 기존 실행은 이전 계획을 유지합니다.`,
            );
          }}
        />
      )}
      <section className="workflow-section">
        <div className="section-title">
          <h2>실행 기록</h2>
          <span className="subtle">정의와 실행 계획을 별도로 보존합니다</span>
        </div>
        {!runs.length && (
          <p className="empty-note">
            정의를 저장한 뒤 실행 준비를 누르세요. 각 단계의 실제 실행에는
            승인이 필요합니다.
          </p>
        )}
        <div className="workflow-run-list">
          {runs.map((r) => (
            <button
              key={r.id}
              className={`workflow-run-item ${run?.id === r.id ? "selected" : ""}`}
              onClick={() => setSelected(r.id)}
            >
              <strong>{r.definition.name}</strong>
              <small>
                v{r.definition.revision} · {date(r.createdAt)} ·{" "}
                {r.id.slice(0, 8)}
              </small>
              <span className={`badge ${r.status.toLowerCase()}`}>
                {labels[r.status] || r.status}
              </span>
            </button>
          ))}
        </div>
      </section>
      {run && (
        <section
          className="workflow-section workflow-execution"
          aria-label="Workflow 실행 상세"
        >
          <div className="section-title">
            <div>
              <h2>
                {run.definition.name} <small>v{run.definition.revision}</small>
              </h2>
              <p className="subtle">
                {run.project.name} · 기준 commit {run.baseCommit.slice(0, 12)}
              </p>
            </div>
            <span className={`badge ${run.status.toLowerCase()}`}>
              {labels[run.status] || run.status}
            </span>
          </div>
          <p className="workflow-run-message">{run.message}</p>
          <p className="policy-note">
            모든 단계는 실행 승인과 결과 승인이 필요합니다. 의존성이 없는 단계는
            함께 준비되며 현재 Runtime은 한 번에 하나씩 실행합니다. 승인된 선행
            변경을 다음 worktree에 적용하며 원본 저장소에는 반영하지 않습니다.
          </p>
          <div className="workflow-stages">
            {run.definition.nodes.map((node) => {
              const step = run.steps[node.id];
              const taskId = step.attempts.at(-1);
              const task = snapshot.tasks.find((t) => t.id === taskId);
              const canRetry =
                ["FAILED", "CANCELLED"].includes(step.status) &&
                !["SUCCEEDED", "CANCELLED"].includes(run.status);
              return (
                <article
                  className={`workflow-stage ${step.status.toLowerCase()}`}
                  key={node.id}
                  aria-label={`단계 ${node.title}`}
                >
                  <div className="workflow-dependencies">
                    <GitBranch size={14} />
                    {node.dependsOn.length ? (
                      node.dependsOn.map((dep) => (
                        <span key={dep}>
                          {run.definition.nodes.find((n) => n.id === dep)
                            ?.title || dep}{" "}
                          <ArrowRight size={12} />
                        </span>
                      ))
                    ) : (
                      <span>시작 단계</span>
                    )}
                  </div>
                  <div className="section-title">
                    <h3>{node.title}</h3>
                    <span className={`badge ${step.status.toLowerCase()}`}>
                      {labels[step.status]}
                    </span>
                  </div>
                  <p className="subtle">
                    {node.id} ·{" "}
                    {snapshot.agents.find((a) => a.id === node.agentId)?.name ||
                      (node.agentId === "auto" ? "Auto 모델 선택" : node.agentId)}{" "}
                    · 시도 {step.attempts.length}/{node.maxAttempts}
                  </p>
                  <p className="workflow-step-message">{step.message}</p>
                  <dl className="workflow-step-meta">
                    <dt>필요 Skill</dt>
                    <dd>{node.requiredSkills.join(", ") || "없음"}</dd>
                    <dt>검증 조건</dt>
                    <dd>
                      {node.requireVerification
                        ? "테스트 통과 필수"
                        : "미확인 결과의 수동 승인 허용"}{" "}
                      ·{" "}
                      {task?.verification === "PASSED"
                        ? "통과"
                        : task?.verification === "FAILED"
                          ? "실패"
                          : "미확인"}
                    </dd>
                    <dt>테스트 명령</dt>
                    <dd>
                      <code>{JSON.stringify(node.testCommand)}</code>
                    </dd>
                  </dl>
                  <details>
                    <summary>단계 지시 보기</summary>
                    <p className="prompt-text">{node.prompt}</p>
                  </details>
                  {task && (
                    <div className="workflow-actions">
                      <button
                        className="button primary"
                        onClick={() => openTask(task.id)}
                      >
                        {task.status === "WAITING_APPROVAL"
                          ? task.agentExitCode === null
                            ? "계획 검토 및 승인"
                            : "결과 검토 및 승인"
                          : "작업·로그·변경 보기"}
                      </button>
                      {task.status === "BLOCKED" && (
                        <>
                          <button className="button" onClick={openAcademy}>
                            Academy 열기
                          </button>
                          <button
                            className="button"
                            disabled={busy}
                            onClick={() =>
                              action(async () => {
                                await api(`/tasks/${task.id}/recheck`, {});
                                await api(
                                  `/workflow-runs/${run.id}/reconcile`,
                                  {},
                                );
                              })
                            }
                          >
                            조건 재검사
                          </button>
                        </>
                      )}
                    </div>
                  )}
                  {canRetry && (
                    <div className="workflow-retry">
                      <label>
                        재시도 보완 지시 (선택)
                        <textarea
                          maxLength={2000}
                          rows={2}
                          value={corrections[node.id] || ""}
                          onChange={(e) =>
                            setCorrections((old) => ({
                              ...old,
                              [node.id]: e.target.value,
                            }))
                          }
                        />
                      </label>
                      <button
                        className="button"
                        disabled={
                          busy || step.attempts.length >= node.maxAttempts
                        }
                        onClick={() =>
                          action(async () => {
                            await api(
                              `/workflow-runs/${run.id}/nodes/${node.id}/retry`,
                              {
                                expectedTaskId: taskId,
                                correction: corrections[node.id] || "",
                              },
                            );
                            setNotice(
                              "새 시도를 준비했습니다. 실행 계획을 다시 승인하세요.",
                            );
                          })
                        }
                      >
                        <RotateCcw size={14} />새 시도 준비
                      </button>
                      <small>
                        {step.attempts.length >= node.maxAttempts
                          ? "재시도 한도 도달 · 정의를 수정하고 새 실행을 만드세요"
                          : "실패한 변경은 보존하며 승인된 선행 결과부터 다시 시작합니다."}
                      </small>
                    </div>
                  )}
                  {!!step.attempts.length && (
                    <div className="workflow-attempts">
                      {step.attempts.map((id, i) => (
                        <button
                          className="button"
                          key={id}
                          onClick={() => openTask(id)}
                        >
                          시도 {i + 1} ·{" "}
                          {labels[
                            snapshot.tasks.find((t) => t.id === id)?.status ||
                              ""
                          ] || "기록"}
                        </button>
                      ))}
                    </div>
                  )}
                  {step.outputDigest && (
                    <p className="subtle">
                      <CheckCircle2 size={12} /> 승인 결과 SHA-256{" "}
                      {step.outputDigest.slice(0, 16)}
                    </p>
                  )}
                </article>
              );
            })}
          </div>
          {!["SUCCEEDED", "CANCELLED"].includes(run.status) && (
            <button
              className="button"
              disabled={busy}
              onClick={() =>
                action(async () => {
                  await api(`/workflow-runs/${run.id}/cancel`, {});
                })
              }
            >
              <Square size={14} />
              Workflow 전체 취소
            </button>
          )}
        </section>
      )}
    </div>
  );
}

function WorkflowEditor({
  value,
  snapshot,
  close,
  save,
}: {
  value: WorkflowDefinition | null;
  snapshot: Snapshot;
  close: () => void;
  save: (body: unknown) => Promise<void>;
}) {
  const defaultAgent = "auto";
  const makeNode = (id: string, dependsOn: string[]): DraftNode => ({
    id,
    title: "새 단계",
    agentId: defaultAgent,
    prompt: "",
    dependsOn,
    requiredSkills: [],
    testCommand: null,
    requireVerification: true,
    timeoutSeconds: 600,
    maxAttempts: 2,
    testText: "",
    skillsText: "",
  });
  const [name, setName] = useState(value?.name || "");
  const [projectId, setProjectId] = useState(
    value?.projectId || snapshot.projects[0]?.id || "",
  );
  const [nodes, setNodes] = useState<DraftNode[]>(
    value
      ? value.nodes.map(draftNode)
      : [
          { ...makeNode("step-1", []), title: "구현" },
          { ...makeNode("step-2", ["step-1"]), title: "리뷰·검증" },
        ],
  );
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const counter = useRef(3);
  const change = (index: number, update: Partial<DraftNode>) =>
    setNodes((old) =>
      old.map((n, i) => (i === index ? { ...n, ...update } : n)),
    );
  return (
    <section
      className="workflow-section workflow-editor"
      aria-label="Workflow 정의 편집"
    >
      <div className="section-title">
        <h2>{value ? `${value.name} 편집` : "새 Workflow"}</h2>
        <button
          className="button"
          type="button"
          disabled={busy}
          onClick={close}
        >
          <X size={14} />
          편집 닫기
        </button>
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            const body = {
              name,
              projectId,
              expectedRevision: value?.revision,
              nodes: nodes.map(({ testText, skillsText, ...node }) => ({
                ...node,
                testCommand: testText.trim() ? JSON.parse(testText) : null,
                requiredSkills: skillsText
                  .split(/[,\n]/)
                  .map((s) => s.trim())
                  .filter(Boolean),
              })),
            };
            await save(body);
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="workflow-form-row">
          <label>
            Workflow 이름
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              maxLength={120}
              placeholder="구현 → 리뷰 → 검증"
            />
          </label>
          <label>
            Workflow 프로젝트
            <select
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              required
            >
              <option value="" disabled>
                프로젝트 선택
              </option>
              {snapshot.projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        <p className="subtle">
          실행 시 프로젝트 규칙·기준 commit·테스트 명령·담당 도구를 고정합니다.
          정의 편집은 기존 실행에 영향을 주지 않습니다.
        </p>
        {nodes.map((node, index) => (
          <fieldset
            className="workflow-node-editor"
            key={node.id}
            disabled={busy}
          >
            <legend>
              단계 {index + 1} · {node.id}
            </legend>
            <div className="workflow-form-row">
              <label>
                단계 이름
                <input
                  value={node.title}
                  onChange={(e) => change(index, { title: e.target.value })}
                  required
                  maxLength={120}
                />
              </label>
              <label>
                담당 직원
                <select
                  value={node.agentId}
                  onChange={(e) => change(index, { agentId: e.target.value })}
                  required
                >
                  <option value="auto">Auto · 단계별 모델·추론 판단 + 독립 리뷰</option>
                  {snapshot.agents.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label>
              단계 작업 지시
              <textarea
                rows={3}
                value={node.prompt}
                onChange={(e) => change(index, { prompt: e.target.value })}
                required
                maxLength={20000}
              />
            </label>
            <div className="workflow-dependency-editor">
              <span>선행 단계 (모두 완료되어야 시작)</span>
              {nodes
                .filter((n) => n.id !== node.id)
                .map((dep) => (
                  <label key={dep.id}>
                    <input
                      type="checkbox"
                      checked={node.dependsOn.includes(dep.id)}
                      onChange={(e) =>
                        change(index, {
                          dependsOn: e.target.checked
                            ? [...node.dependsOn, dep.id]
                            : node.dependsOn.filter((id) => id !== dep.id),
                        })
                      }
                    />
                    {dep.title} ({dep.id})
                  </label>
                ))}
            </div>
            <div className="workflow-form-row">
              <label>
                필요한 Skill 이름
                <input
                  value={node.skillsText}
                  onChange={(e) =>
                    change(index, { skillsText: e.target.value })
                  }
                  placeholder="readme-guide, code-review"
                />
              </label>
              <label>
                단계 테스트 명령
                <input
                  value={node.testText}
                  onChange={(e) => change(index, { testText: e.target.value })}
                  placeholder="빈칸: 프로젝트 명령 상속 / []: 미실행"
                  spellCheck={false}
                />
              </label>
            </div>
            <label className="workflow-checkbox">
              <input
                type="checkbox"
                checked={node.requireVerification}
                onChange={(e) =>
                  change(index, { requireVerification: e.target.checked })
                }
              />
              테스트 통과 필수
            </label>
            <div className="workflow-form-row">
              <label>
                단계 시간 제한 (초)
                <input
                  type="number"
                  min={10}
                  max={3600}
                  value={node.timeoutSeconds}
                  onChange={(e) =>
                    change(index, { timeoutSeconds: Number(e.target.value) })
                  }
                />
              </label>
              <label>
                최대 시도 횟수 (최초 포함)
                <input
                  type="number"
                  min={1}
                  max={5}
                  value={node.maxAttempts}
                  onChange={(e) =>
                    change(index, { maxAttempts: Number(e.target.value) })
                  }
                />
              </label>
            </div>
            <p className="subtle">
              각 시도의 실행 계획과 결과는 사용자가 승인합니다. 실패 후 재시도는
              명시적으로 준비하며 자동 반복 호출하지 않습니다.
            </p>
            <button
              className="button"
              type="button"
              disabled={nodes.length === 1}
              onClick={() =>
                setNodes((old) =>
                  old
                    .filter((n) => n.id !== node.id)
                    .map((n) => ({
                      ...n,
                      dependsOn: n.dependsOn.filter((id) => id !== node.id),
                    })),
                )
              }
            >
              단계 삭제
            </button>
          </fieldset>
        ))}
        <div className="workflow-actions">
          <button
            className="button"
            disabled={busy || nodes.length >= 20}
            type="button"
            onClick={() => {
              let id: string;
              do {
                id = `step-${counter.current++}`;
              } while (nodes.some((n) => n.id === id));
              setNodes((old) => [...old, makeNode(id, [old.at(-1)!.id])]);
            }}
          >
            <Plus size={14} />
            단계 추가
          </button>
          <button
            className="button primary"
            disabled={busy || !projectId}
            type="submit"
          >
            {busy ? "저장 중…" : "Workflow 저장"}
          </button>
        </div>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
      </form>
    </section>
  );
}
