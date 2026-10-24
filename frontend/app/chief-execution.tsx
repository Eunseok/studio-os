"use client";
import { useState } from "react";
import { taskLabel } from "./studio-model";
import type { Conversation } from "./chief-data";
import type { Snapshot, Task, WorkflowRun } from "./types";

function Evidence({ task }: { task: Task }) {
  const [evidence, setEvidence] = useState<{ diff: string; logs: Record<string, string> }>();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  async function load() {
    setLoading(true);
    setError("");
    try {
      const read = async (path: string) => {
        const response = await fetch(`/api/tasks/${task.id}/${path}`, { cache: "no-store" });
        const value = await response.json();
        if (!response.ok) throw new Error(value.message || "실행 근거를 불러오지 못했습니다");
        return value;
      };
      const [changes, logs] = await Promise.all([read("changes"), read("logs")]);
      setEvidence({ diff: changes.diff, logs });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }
  return <details onToggle={event => { if (event.currentTarget.open && !evidence && !loading) void load(); }}>
    <summary>실제 변경·검증·리뷰 근거</summary>
    <button className="button" disabled={loading} onClick={() => void load()}>근거 새로 불러오기</button>
    {loading && <p role="status">실행 근거를 불러오는 중입니다.</p>}
    {error && <p role="alert" className="form-error">{error}</p>}
    {evidence && <>
      <h4>변경 내용</h4><pre>{evidence.diff || "기록된 변경이 없습니다."}</pre>
      {Object.entries(evidence.logs).map(([name, content]) => <details key={name}><summary>{name}</summary><pre>{content || "로그가 비어 있습니다."}</pre></details>)}
      {!Object.keys(evidence.logs).length && <p>아직 실행 로그가 없습니다.</p>}
    </>}
  </details>;
}
const reviewLabels: Record<string, string> = { NOT_RUN: "미실행", RUNNING: "진행 중", PASSED: "통과", CHANGES_REQUIRED: "수정 필요", FAILED: "실패" };
function Attempt({ task: t, snapshot, openTask }: { task: Task; snapshot: Snapshot; openTask: (id: string) => void }) {
  const implementation = t.agentExitCode != null ? `종료 코드 ${t.agentExitCode}${t.agentExitCode === 0 ? " · 구현 실행 완료" : " · 구현 실행 실패"}`
    : t.status === "CANCELLED" ? "중지됨 · 완료 확인 없음" : t.startedAt ? "실행 시작 · 완료 확인 없음" : "미실행";
  return <article className="chief-attempt" aria-label={`${t.title} 시도 ${t.workflowAttempt || t.attempt || 1}`}>
    <div className="section-title"><strong>시도 {t.workflowAttempt || t.attempt || 1} · {taskLabel(t)}</strong><button className="button" onClick={() => openTask(t.id)}>변경·근거 보기</button></div>
    <dl className="chief-evidence-grid">
      <dt>전문가·모델</dt><dd>{snapshot.agents.find(a => a.id === t.agentId)?.name || "담당자 배정 중"} · {t.role || "직무 기록 없음"} · {[t.adapter, t.model, t.reasoning].filter(Boolean).join(" · ") || "선정 대기"}</dd>
      <dt>선정 이유</dt><dd>{t.routingError || t.routingReason || "선정 근거가 아직 없습니다."}</dd>
      <dt>고정된 Skill</dt><dd>{t.skills?.length ? t.skills.map(s => <span className="chief-skill" key={s.id}>{s.name} · {s.version}<small>SHA-256 {s.digest}</small></span>) : "고정된 Skill 없음"}{!!t.requiredSkills?.length && <p>요청한 Skill: {t.requiredSkills.join(", ")}</p>}</dd>
      <dt>구현</dt><dd>{implementation}</dd>
      <dt>검증</dt><dd>{t.verification === "PASSED" ? "통과" : t.verification === "FAILED" ? "실패" : "미실행·통과 확인 없음"}{t.testExitCode != null && ` · 종료 코드 ${t.testExitCode}`}<code>{t.testCommand?.length ? JSON.stringify(t.testCommand) : "검증 명령 없음"}</code></dd>
      <dt>독립 리뷰</dt><dd>{reviewLabels[t.reviewStatus || "NOT_RUN"] || t.reviewStatus}{t.reviewerModel && <p>{[t.reviewerProvider, t.reviewerModel, t.reviewerReasoning].filter(Boolean).join(" · ")}</p>}{t.review && <><p>{t.review.summary}</p><ul>{t.review.findings.map((f, i) => <li key={i}>{f.severity} · {f.file} · {f.detail}</li>)}</ul></>}</dd>
      <dt>프로젝트 반영</dt><dd>{t.appliedCommit ? `반영 기록 있음 · ${t.appliedCommit}` : "미반영 · 격리 작업 공간의 결과"}</dd>
    </dl>
    <p>{t.message}</p>
    {t.failure && <p role="status">실패 분류: {t.failure.category} · {t.failure.reason} · {t.failure.adapt ? "허용 횟수 내 보완 재시도 대상" : "자동 재시도 중단"}</p>}
    {t.requirements?.filter(r => !r.available).map(r => <p key={r.name}>{r.name}: {r.detail}</p>)}
    <Evidence task={t} />
    {t.startedAt && <p><a href={`/api/tasks/${t.id}/artifacts/execution.json`}>실제 실행 기록</a> · <a href={`/api/tasks/${t.id}/artifacts/skills.json`}>고정된 Skill 기록</a></p>}
  </article>;
}
function Run({ run, conversation, snapshot, openTask, previous = false }: { run: WorkflowRun; conversation: Conversation; snapshot: Snapshot; openTask: (id: string) => void; previous?: boolean }) {
  return <section className="chief-progress" aria-label={previous ? "이전 실행 기록" : "실행 결과와 근거"}>
    <h3>{previous ? "이전 실행 기록" : "전문가 팀 진행 상황"}</h3>
    <p>{run.message} · 실행 상태: {run.status}</p>
    {!previous && <p>결과 확인: {conversation.state === "COMPLETE" ? "완료 승인 기록 있음" : "사용자 완료 승인 전"}. 프로젝트 반영은 각 작업의 반영 기록으로 확인합니다.</p>}
    {run.definition.nodes.map((node, index) => {
      const step = run.steps[node.id];
      const reason = (!previous && conversation.plan?.steps[index]?.skillReason) || node.prompt.split("\nSkill 선정 근거: ")[1];
      return <section className="chief-step" key={node.id} aria-label={node.title}>
        <h4>{index + 1}. {node.title}</h4>
        {reason && <p>Skill 선정 이유: {reason}</p>}
        {!reason && <p>Skill 선정 이유: 별도 근거 기록 없음</p>}
        {!step?.attempts.length && <p>{step?.message || "단계 준비 대기"} · 아직 수행하지 않았습니다.</p>}
        {step?.attempts.map(id => {
          const task = snapshot.tasks.find(t => t.id === id);
          return task ? <Attempt key={id} task={task} snapshot={snapshot} openTask={openTask} /> : <p key={id}>작업 {id}의 기록을 불러오지 못했습니다.</p>;
        })}
      </section>;
    })}
  </section>;
}
export default function ChiefExecution({ conversation, snapshot, openTask }: { conversation: Conversation; snapshot: Snapshot; openTask: (id: string) => void }) {
  const run = snapshot.workflowRuns.find(r => r.id === conversation.runId);
  return <>
    {conversation.runId && (run ? <Run run={run} conversation={conversation} snapshot={snapshot} openTask={openTask} /> : <p role="status">실행 기록을 불러오지 못했습니다. 결과를 확인하기 전에 다시 불러오세요.</p>)}
    {!!conversation.previousRunIds?.length && <details><summary>이전 실행·중단 이력 ({conversation.previousRunIds.length})</summary>{conversation.previousRunIds.map(id => {
      const previous = snapshot.workflowRuns.find(r => r.id === id);
      return previous ? <Run key={id} run={previous} conversation={conversation} snapshot={snapshot} openTask={openTask} previous /> : <p key={id}>이전 실행 {id}의 기록을 불러오지 못했습니다.</p>;
    })}</details>}
  </>;
}
