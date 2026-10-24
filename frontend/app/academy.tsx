"use client";
import { useState } from "react";
import {
  BookOpen,
  CheckCircle2,
  XCircle,
  ScanSearch,
  Plus,
  ArrowRight,
} from "lucide-react";
import type { Snapshot, Requirement } from "./types";
export type Skill = {
  id: string;
  projectId: string | null;
  name: string;
  description: string;
  version: string;
  source: string;
  digest: string;
  status: string;
  validatedAt: string | null;
  checks: Requirement[];
  requiredTools: string[];
  files?: Record<string, string>;
};
const states: Record<string, string> = {
  DISCOVERED: "발견됨",
  REVIEWING: "검토 중",
  ACTIVE: "활성",
  DISABLED: "비활성",
};
async function api<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(
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
  const data = await r.json();
  if (!r.ok) throw new Error(data.message || "요청 실패");
  return data;
}
const template =
  "---\nname: readme-guide\ndescription: README 문서를 일관된 형식으로 작성합니다.\nversion: 1.0.0\nrequired-tools: [git]\n---\n# README guide\n기존 문서 구조를 유지하고 변경 후 git diff를 검토하세요.\n";
export default function Academy({
  snapshot,
  refresh,
  openTask,
}: {
  snapshot: Snapshot;
  refresh: () => Promise<void>;
  openTask: (id: string) => void;
}) {
  const [scope, setScope] = useState("");
  const [includeGlobal, setIncludeGlobal] = useState(true);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("all");
  const [visibleCount, setVisibleCount] = useState(24);
  const [selected, setSelected] = useState<Skill | null>(null);
  const [register, setRegister] = useState(false);
  const [mode, setMode] = useState("content");
  const [content, setContent] = useState(template);
  const [path, setPath] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [issues, setIssues] = useState<string[]>([]);
  const all = snapshot.skills || [];
  const shown = all.filter(
    (s) =>
      (!scope || s.projectId === scope || s.projectId === null) &&
      (filter === "all" || s.status === filter) &&
      `${s.name} ${s.description}`.toLowerCase().includes(query.toLowerCase()),
  );
  const blocked = snapshot.tasks.filter((t) => t.status === "BLOCKED");
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
    <div className="academy">
      <div className="notice">
        <BookOpen size={24} />
        <div>
          <h2>역량을 준비하고, 막힌 작업을 이어가세요</h2>
          <p>
            발견 → 등록 → 검증 → 사용 승인 → 작업 재검사. Skill의 내용과 버전을
            고정해 실행합니다.
          </p>
        </div>
      </div>
      <div className="academy-metrics">
        <div>
          <strong>{new Set(all.map(s=>s.name)).size}</strong>
          <span>발견한 Skill 종류 · 버전 중복 제외</span>
        </div>
        <div>
          <strong>{all.filter((s) => s.status === "ACTIVE").length}</strong>
          <span>사용 승인된 Skill</span>
        </div>
        <div>
          <strong>{blocked.length}</strong>
          <span>Skill 준비가 필요한 작업</span>
        </div>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="academy-notice" role="status">
          {notice}
        </p>
      )}
      <section className="academy-section">
        <div className="section-title">
          <h2>설치된 Skill</h2>
          <button
            className="button primary"
            onClick={() => {
              setRegister(!register);
              setSelected(null);
            }}
          >
            <Plus size={15} />
            Skill 등록
          </button>
        </div>
        <div className="academy-toolbar">
          <label>
            등록·스캔 범위
            <select
              value={scope}
              onChange={(e) => {
                setScope(e.target.value);
                setVisibleCount(24);
              }}
              disabled={busy}
            >
              <option value="">전역</option>
              {snapshot.projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <label className="academy-checkbox">
            <input
              type="checkbox"
              checked={includeGlobal}
              onChange={(e) => setIncludeGlobal(e.target.checked)}
            />
            전역 폴더 포함
          </label>
          <button
            className="button"
            disabled={busy || (!scope && !includeGlobal)}
            onClick={() =>
              action(async () => {
                const r = await api<{ skills: Skill[]; issues: string[] }>(
                  "/skills/scan",
                  { projectId: scope || null, includeGlobal },
                );
                setIssues(r.issues);
                setNotice(
                  `${r.skills.length}개 Skill을 확인했습니다. 사용하려면 검증과 승인이 필요합니다.`,
                );
              })
            }
          >
            <ScanSearch size={16} />
            {busy ? "처리 중…" : "설치 Skill 스캔"}
          </button>
        </div>
        <p className="subtle">
          프로젝트의 .agents/skills · .codex/skills · .claude/skills · skills,
          전역의 ~/.agents/skills · ~/.codex/skills · ~/.claude/skills를
          읽습니다. 기존 설치 파일은 변경하지 않습니다.
        </p>
        {issues.length > 0 && (
          <details className="academy-issues">
            <summary>스캔에서 제외된 항목 {issues.length}개</summary>
            <ul>
              {issues.map((i, n) => (
                <li key={n}>{i}</li>
              ))}
            </ul>
          </details>
        )}
        {register && (
          <form
            className="academy-register"
            onSubmit={(e) => {
              e.preventDefault();
              action(async () => {
                const s = await api<Skill>("/skills", {
                  projectId: scope || null,
                  ...(mode === "content" ? { content } : { path }),
                });
                setSelected(s);
                setRegister(false);
                setNotice(
                  "내용 스냅샷을 등록했습니다. 검증 후 사용을 승인하세요.",
                );
              });
            }}
          >
            <h3>새 Skill 버전 등록</h3>
            <p className="subtle">
              등록 범위:{" "}
              {snapshot.projects.find((p) => p.id === scope)?.name || "전역"}.
              변경한 내용을 다시 등록하면 별도 버전으로 보존됩니다.
            </p>
            <label>
              등록 방식
              <select value={mode} onChange={(e) => setMode(e.target.value)}>
                <option value="content">SKILL.md 직접 작성</option>
                <option value="path">로컬 패키지 경로</option>
              </select>
            </label>
            {mode === "content" ? (
              <label>
                SKILL.md 내용
                <textarea
                  rows={12}
                  required
                  value={content}
                  onChange={(e) => setContent(e.target.value)}
                  spellCheck={false}
                />
              </label>
            ) : (
              <label>
                SKILL.md 절대 경로
                <input
                  value={path}
                  onChange={(e) => setPath(e.target.value)}
                  required
                  placeholder="/Users/me/skills/readme-guide/SKILL.md"
                />
              </label>
            )}
            <p className="subtle">
              UTF-8 텍스트 패키지 · 파일당 128 KiB / 전체 1 MiB / 100개. 링크
              파일·바이너리는 지원하지 않습니다.
            </p>
            <button className="button primary" disabled={busy} type="submit">
              스냅샷 등록
            </button>
          </form>
        )}
        <div className="academy-toolbar">
          <label>
            Skill 검색
            <input
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setVisibleCount(24);
              }}
              placeholder="이름 또는 설명"
            />
          </label>
          <label>
            상태
            <select
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value);
                setVisibleCount(24);
              }}
            >
              <option value="all">전체 상태</option>
              {Object.entries(states).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="skill-grid">
          {shown.slice(0, visibleCount).map((s) => (
            <button
              className={`skill-card ${selected?.id === s.id ? "selected" : ""}`}
              key={s.id}
              disabled={busy}
              onClick={() =>
                action(async () =>
                  setSelected(await api<Skill>(`/skills/${s.id}`)),
                )
              }
            >
              <span className="skill-card-top">
                <BookOpen size={18} />
                <span className={`skill-state ${s.status.toLowerCase()}`}>
                  {states[s.status]}
                </span>
              </span>
              <strong>{s.name}</strong>
              <p>{s.description || "형식을 확인하세요"}</p>
              <span className="skill-version">
                {s.status === "ACTIVE" ? "사용 가능" : "상세에서 내용과 사용 조건을 확인하세요"}
              </span>
              <small>
                {snapshot.projects.find((p) => p.id === s.projectId)?.name ||
                  "전역"}
              </small>
            </button>
          ))}
        </div>
        {shown.length > visibleCount && (
          <button
            className="button"
            onClick={() => setVisibleCount((n) => n + 24)}
          >
            더 보기 ({visibleCount} / {shown.length})
          </button>
        )}
        {!shown.length && (
          <p className="empty-note">
            등록된 Skill이 없습니다. 설치 폴더를 스캔하거나 직접 등록하세요.
          </p>
        )}
      </section>
      {selected && (
        <section
          className="academy-section skill-detail"
          aria-label="Skill 상세"
        >
          <div className="section-title">
            <h2>
              {selected.name} <small>{selected.version}</small>
            </h2>
            <button className="button" onClick={() => setSelected(null)}>
              상세 닫기
            </button>
          </div>
          <p>{selected.description}</p>
          <p className="subtle">{selected.source}</p>
          <code className="skill-hash">SHA-256 {selected.digest}</code>
          <p className="policy-note">
            검증은 형식, 참조 파일, CLI 존재 여부를 확인합니다. 스크립트를
            실행하거나 기능의 정확성을 인증하지 않습니다. 아래 전체 파일을
            검토한 후 사용을 승인하세요. 실행 결과는 각 작업의 테스트와 결과
            승인으로 검증합니다.
          </p>
          {selected.checks.map((r, i) => (
            <div
              className={`requirement ${r.available ? "" : "missing"}`}
              key={i}
            >
              {r.available ? <CheckCircle2 size={17} /> : <XCircle size={17} />}
              <span>
                <strong>{r.name}</strong>
                <small>{r.detail}</small>
              </span>
            </div>
          ))}
          {Object.entries(selected.files || {}).map(([name, text]) => (
            <details key={name} open={name === "SKILL.md"}>
              <summary>{name}</summary>
              <pre>{text}</pre>
            </details>
          ))}
          <div className="academy-actions">
            <button
              className="button"
              disabled={busy}
              onClick={() =>
                action(async () =>
                  setSelected(
                    await api<Skill>(`/skills/${selected.id}/validate`, {}),
                  ),
                )
              }
            >
              검증 실행
            </button>
            <button
              className="button primary"
              disabled={
                busy ||
                !selected.validatedAt ||
                selected.checks.some((c) => !c.available) ||
                selected.status === "ACTIVE"
              }
              onClick={() =>
                action(async () => {
                  setSelected(
                    await api<Skill>(`/skills/${selected.id}/activate`, {}),
                  );
                  setNotice(
                    "사용 승인 완료. 차단된 작업을 재검사하면 새 버전으로 실행 계획을 준비합니다.",
                  );
                })
              }
            >
              사용 승인 및 활성화
            </button>
            {selected.status === "ACTIVE" && (
              <button
                className="button"
                disabled={busy}
                onClick={() =>
                  action(async () =>
                    setSelected(
                      await api<Skill>(`/skills/${selected.id}/disable`, {}),
                    ),
                  )
                }
              >
                비활성화
              </button>
            )}
          </div>
        </section>
      )}
      <section className="academy-section">
        <div className="section-title">
          <h2>부족한 역량과 차단된 작업</h2>
          <span className="subtle">해결 → 재검사 → 실행 승인</span>
        </div>
        {!blocked.length && (
          <p className="empty-note">현재 차단된 작업이 없습니다.</p>
        )}
        {blocked.map((t) => (
          <article className="academy-blocked" key={t.id}>
            <div>
              <h3>{t.title}</h3>
              {t.requirements
                .filter((r) => !r.available)
                .map((r) => (
                  <p key={r.name}>
                    <strong>{r.name}</strong> · {r.detail}
                  </p>
                ))}
            </div>
            <div className="academy-actions">
              <button
                className="button"
                disabled={busy}
                onClick={() =>
                  action(async () => {
                    const next = await api<{ status: string }>(
                      `/tasks/${t.id}/recheck`,
                      {},
                    );
                    setNotice(
                      next.status === "WAITING_APPROVAL"
                        ? "조건을 충족했습니다. 작업을 열어 계획을 승인하세요."
                        : "아직 부족한 조건이 있습니다. 표시된 항목을 해결하세요.",
                    );
                  })
                }
              >
                조건 재검사
              </button>
              <button className="button" onClick={() => openTask(t.id)}>
                작업 열기
                <ArrowRight size={15} />
              </button>
            </div>
          </article>
        ))}
      </section>
      <section className="academy-section">
        <h2>실행 환경</h2>
        <div className="requirements-list">
          {snapshot.adapters.map((a) => (
            <article key={a.type}>
              <h3>{a.type} 어댑터</h3>
              {a.requirements.map((r) => (
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
            </article>
          ))}
        </div>
        <p className="subtle">
          테스트 OS sandbox:{" "}
          {snapshot.verificationSandbox ? "사용 가능" : "설치 필요"}
        </p>
      </section>
    </div>
  );
}
