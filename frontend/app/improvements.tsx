"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Snapshot } from "./types";
type Idea = {
  id: string;
  projectId: string;
  feedback: string;
  createdAt: string;
  analysisTaskId: string;
  analysisAttempts: string[];
  workflowRunId: string | null;
  selectedProposalId: string | null;
};
export type Proposal = {
  id: string;
  title: string;
  rationale: string;
  evidence: string[];
  effort: string;
  risks: string[];
  acceptance: string[];
  implementationPrompt: string;
  requiredSkills: string[];
};
const labels: Record<string, string> = {
  WAITING_APPROVAL: "승인 대기",
  RUNNING: "작업 중",
  VERIFYING: "검증 중",
  SUCCEEDED: "완료",
  FAILED: "실패",
  CANCELLED: "취소",
  BLOCKED: "조건 부족",
  QUEUED: "실행 대기",
  ANALYZING: "준비 중",
};
async function api<T>(url: string, data?: unknown): Promise<T> {
  const r = await fetch(
    "/api/improvements" + url,
    data === undefined
      ? { cache: "no-store" }
      : {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Studio-Client": "1",
          },
          body: JSON.stringify(data),
        },
  );
  const body = await r.json();
  if (!r.ok) throw new Error(body.message || "요청 실패");
  return body;
}
export default function Improvements({
  snapshot,
  refresh,
  openTask,
  navigate,
}: {
  snapshot: Snapshot;
  refresh: () => Promise<void>;
  openTask: (id: string) => void;
  navigate: (page: string) => void;
}) {
  const [ideas, setIdeas] = useState<Idea[]>([]),
    [project, setProject] = useState(""),
    [feedback, setFeedback] = useState(""),
    [provider, setProvider] = useState("auto"),
    [execution, setExecution] = useState("auto"),
    [selected, setSelected] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const requestKey = useRef("");
  const load = useCallback(async () => {
    setIdeas(await api<Idea[]>(""));
  }, []);
  useEffect(() => {
    void load().catch((e) => setError(e.message));
  }, [load, snapshot.eventCursor]);
  const idea = ideas.find((i) => i.id === selected) || ideas[0];
  const analysis = snapshot.tasks.find((t) => t.id === idea?.analysisTaskId);
  const run = snapshot.workflowRuns.find((r) => r.id === idea?.workflowRunId);
  const step = run?.steps.implement,
    implementation = snapshot.tasks.find((t) => t.id === step?.attempts.at(-1));
  const act = async (work: () => Promise<void>) => {
    setBusy(true);
    setError("");
    try {
      await work();
      await refresh();
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="improvements" aria-label="대표실 개선함">
      <div className="improvement-intro">
        <span>CEO OFFICE / CONTINUOUS IMPROVEMENT</span>
        <h2>불편한 점 하나에서, 검증된 개선까지</h2>
        <p>
          의견 → 읽기 전용 AI 조사 → 후보 검토·선택 → 구현 계획 승인 → 코드
          수정·검증 → 결과 승인
        </p>
        <p>
          총괄·PM·기술 검토는 조사 역할로, 개발·QA는 구현과 검증으로 수행합니다.
          역할마다 별도 AI를 상주시킬 필요는 없습니다.
        </p>
      </div>
      {busy && (
        <p role="status">
          AI 모델 선택 또는 계획을 준비하고 있습니다. 선택 결과가 검증되기
          전에는 구현을 시작하지 않습니다.
        </p>
      )}
      {error && (
        <p role="alert" className="improvement-error">
          {error}
        </p>
      )}
      <form
        className="improvement-form"
        onSubmit={(e) => {
          e.preventDefault();
          void act(async () => {
            if (!requestKey.current) requestKey.current = crypto.randomUUID();
            const i = await api<Idea>("", {
              projectId: project,
              feedback,
              provider,
              requestKey: requestKey.current,
            });
            setSelected(i.id);
            requestKey.current = "";
            setFeedback("");
            await refresh();
            openTask(i.analysisTaskId);
          });
        }}
      >
        <label>
          개선할 프로젝트
          <select
            required
            value={project}
            onChange={(e) => {
              setProject(e.target.value);
              requestKey.current = "";
            }}
          >
            <option value="">프로젝트 선택</option>
            {snapshot.projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          조사 실행 도구
          <select
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            <option value="auto">AI가 도구·모델·추론 수준 판단</option>
            <option value="codex">Codex</option>
            <option value="claude">Claude</option>
          </select>
        </label>
        <label className="wide">
          개선 의견
          <textarea
            required
            maxLength={4000}
            rows={4}
            value={feedback}
            onChange={(e) => {
              setFeedback(e.target.value);
              requestKey.current = "";
            }}
            placeholder="예: 처음 들어왔을 때 무엇을 해야 할지 모르겠어요. 또는 현재 코드에서 작은 개선 3개를 제안해 주세요."
          />
        </label>
        <p className="wide">
          저장 후 실행 계획에서 모델·추론 수준·선택 이유를 확인하고 승인하세요.
          자동 모드에서는 먼저 읽기 전용 AI 모델 선택 호출이 발생하며 최대
          90초가 걸릴 수 있습니다. 선택 후 분석 실행은 승인을 기다립니다.
        </p>
        <button className="primary" disabled={busy || !project}>
          의견 저장 · 분석 계획 만들기
        </button>
        <button type="button" onClick={() => navigate("projects")}>
          프로젝트 등록·검증 명령 설정
        </button>
      </form>
      <div className="improvement-layout">
        <aside aria-label="개선 의견 목록">
          {ideas.map((i) => (
            <button
              key={i.id}
              aria-pressed={idea?.id === i.id}
              onClick={() => setSelected(i.id)}
            >
              <strong>
                {snapshot.projects.find((p) => p.id === i.projectId)?.name}
              </strong>
              <span>{i.feedback}</span>
              <small>{new Date(i.createdAt).toLocaleString("ko-KR")}</small>
            </button>
          ))}
          {!ideas.length && <p>첫 개선 의견을 남겨보세요.</p>}
        </aside>
        {idea && (
          <div className="improvement-detail">
            <h2>대표의 개선 의견</h2>
            <p className="improvement-feedback">{idea.feedback}</p>
            {analysis && (
              <div className="improvement-progress">
                <strong>AI 조사 · {labels[analysis.status]}</strong>
                <p>
                  {analysis.model || analysis.adapter} · 추론{" "}
                  {analysis.reasoning || "기본"}
                </p>
                <p>{analysis.routingReason}</p>
                <button onClick={() => openTask(analysis.id)}>
                  분석 계획·로그·결과 승인
                </button>
                {["FAILED", "CANCELLED"].includes(analysis.status) &&
                  !idea.workflowRunId && (
                    <button
                      disabled={busy}
                      onClick={() =>
                        void act(async () => {
                          const i = await api<Idea>(`/${idea.id}/retry`, {
                            expectedTaskId: analysis.id,
                            provider,
                          });
                          await refresh();
                          openTask(i.analysisTaskId);
                        })
                      }
                    >
                      분석 재시도
                    </button>
                  )}
              </div>
            )}
            {!analysis?.improvementProposals?.length && (
              <p className="empty-note">
                분석 계획을 승인하면 AI가 저장소를 조사합니다. 검증된 제안이
                여기에 표시됩니다.
              </p>
            )}
            {!!analysis?.improvementProposals?.length && (
              <>
                <h3>AI 개선 후보</h3>
                {analysis.status !== "SUCCEEDED" && (
                  <p>
                    먼저 후보의 근거와 범위를 검토하고 분석 결과를 승인하세요.
                  </p>
                )}
                <label>
                  구현 실행 도구
                  <select
                    value={execution}
                    onChange={(e) => setExecution(e.target.value)}
                  >
                    <option value="auto">AI가 도구·모델·추론 수준 판단</option>
                    <option value="codex">Codex</option>
                    <option value="claude">Claude</option>
                  </select>
                </label>
              </>
            )}
            {analysis?.improvementProposals?.map((p) => (
              <article
                className="improvement-proposal"
                key={p.id}
                aria-label={`개선 후보 ${p.title}`}
              >
                <header>
                  <h3>{p.title}</h3>
                  <span>
                    {p.effort === "small"
                      ? "작은 작업"
                      : p.effort === "large"
                        ? "큰 작업"
                        : "중간 작업"}
                  </span>
                </header>
                <p>{p.rationale}</p>
                <h4>조사 근거</h4>
                <ul>
                  {p.evidence.map((v, n) => (
                    <li key={n}>{v}</li>
                  ))}
                </ul>
                <h4>완료 조건</h4>
                <ul>
                  {p.acceptance.map((v, n) => (
                    <li key={n}>{v}</li>
                  ))}
                </ul>
                <h4>위험과 필요한 역량</h4>
                <p>{p.risks.join(" · ") || "별도 위험 미기재"}</p>
                <p>{p.requiredSkills.join(", ") || "추가 Skill 요청 없음"}</p>
                <details>
                  <summary>구현 지시 보기</summary>
                  <p>{p.implementationPrompt}</p>
                </details>
                <button
                  className="primary"
                  disabled={
                    busy ||
                    analysis.status !== "SUCCEEDED" ||
                    !!idea.workflowRunId
                  }
                  onClick={() =>
                    void act(async () => {
                      const i = await api<Idea>(`/${idea.id}/select`, {
                        proposalId: p.id,
                        provider: execution,
                      });
                      setSelected(i.id);
                    })
                  }
                >
                  {idea.selectedProposalId === p.id
                    ? "선택한 개선안"
                    : "이 개선안으로 구현 계획 만들기"}
                </button>
              </article>
            ))}
            {run && (
              <div className="improvement-progress">
                <h3>선택한 개선 실행 · {labels[run.status] || run.status}</h3>
                <p>{run.message}</p>
                {implementation && (
                  <>
                    <p>
                      {implementation.model || implementation.adapter} · 추론{" "}
                      {implementation.reasoning || "기본"} · 검증{" "}
                      {implementation.verification} · 시도{" "}
                      {step?.attempts.length}
                    </p>
                    <button onClick={() => openTask(implementation.id)}>
                      코드 변경·검증·결과 승인
                    </button>
                  </>
                )}
                <button onClick={() => navigate("workflows")}>
                  Workflow에서 재시도·취소 관리
                </button>
                <button onClick={() => navigate("overview")}>
                  가상 스튜디오에서 보기
                </button>
                {run.status === "SUCCEEDED" && (
                  <p>
                    검증과 결과 승인이 완료됐습니다. 변경은 격리 worktree에
                    보존되며 실행 중인 스튜디오에 자동 반영되지 않습니다.
                  </p>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
