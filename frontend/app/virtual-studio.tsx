"use client";
import { useState } from "react";
import {
  projectStudio,
  roomNames,
  taskRoom,
  taskLabel,
  studioLabels,
  type Room,
} from "./studio-model";
import type { Snapshot, StudioEvent } from "./types";

export default function VirtualStudio({
  snapshot,
  events,
  connected,
  updatedAt,
  openTask,
  navigate,
}: {
  snapshot: Snapshot;
  events: StudioEvent[];
  connected: boolean;
  updatedAt: number;
  openTask: (id: string) => void;
  navigate: (page: string) => void;
}) {
  const [project, setProject] = useState(""),
    [list, setList] = useState(false),
    [paused, setPaused] = useState(false),
    [employee, setEmployee] = useState("");
  const model = projectStudio(snapshot, project);
  const selected = model.employees.find((e) => e.agent.id === employee);
  const pending = model.tasks.filter(
    (t) => !["SUCCEEDED", "CANCELLED"].includes(t.status),
  );
  return (
    <section
      className={`virtual-studio ${paused || !connected ? "motion-paused" : ""}`}
      aria-label="가상 스튜디오"
    >
      <header className="vs-toolbar">
        <div>
          <span className="vs-eyebrow">LIVE OPERATIONS / STUDIO 04</span>
          <h2>일하는 AI 스튜디오</h2>
          <p>직원과 공간을 선택하면 실제 작업으로 이어집니다.</p>
        </div>
        <div className="vs-controls">
          <label>
            프로젝트{" "}
            <select
              aria-label="스튜디오 프로젝트"
              value={project}
              onChange={(e) => setProject(e.target.value)}
            >
              <option value="">전체 프로젝트</option>
              {snapshot.projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <button aria-pressed={list} onClick={() => setList(!list)}>
            {list ? "공간 보기" : "목록 보기"}
          </button>
          <button aria-pressed={paused} onClick={() => setPaused(!paused)}>
            {paused ? "연출 재생" : "연출 정지"}
          </button>
        </div>
      </header>
      <div className="vs-connection" role="status">
        <span className={connected ? "vs-live" : ""} />{" "}
        {connected ? "실시간 연결" : "연결 복구 중 · 마지막 수신 상태"}{" "}
        <small>
          최근 동기화{" "}
          {updatedAt ? new Date(updatedAt).toLocaleTimeString("ko-KR") : "대기"}{" "}
          · 이벤트 #{snapshot.eventCursor}
        </small>
      </div>
      <div className={`vs-map ${list ? "vs-list" : ""}`}>
        {(Object.keys(roomNames) as Room[]).map((room, index) => {
          const occupants = model.employees.filter((e) => e.room === room);
          const roomTasks = pending.filter((t) => taskRoom(t) === room);
          return (
            <section
              className={`vs-room vs-${room}`}
              key={room}
              aria-label={roomNames[room]}
            >
              <header>
                <span className="vs-room-number">0{index + 1}</span>
                <h3>{roomNames[room]}</h3>
                <span>
                  {roomTasks.length > 0 ? `${roomTasks.length}개 작업` : ""}
                </span>
              </header>
              <div className="vs-occupants">
                {occupants.map((e) => (
                  <button
                    key={e.agent.id}
                    className={`vs-person ${e.current?.status.toLowerCase() || "idle"}`}
                    aria-pressed={employee === e.agent.id}
                    onClick={() =>
                      setEmployee(employee === e.agent.id ? "" : e.agent.id)
                    }
                  >
                    <span className="vs-avatar" aria-hidden="true">
                      <i />
                      <b />
                    </span>
                    <strong>{e.agent.name}</strong>
                    <small>
                      {e.current ? taskLabel(e.current) : "대기 중"}
                    </small>
                    <small>
                      {
                        e.history.filter(
                          (t) =>
                            !["SUCCEEDED", "FAILED", "CANCELLED"].includes(
                              t.status,
                            ),
                        ).length
                      }
                      개 진행 · {e.agent.adapter}
                    </small>
                  </button>
                ))}
              </div>
              <div className="vs-room-tasks">
                {roomTasks.map((t) => (
                  <button
                    key={t.id}
                    onClick={() => openTask(t.id)}
                    data-task-id={t.id}
                  >
                    <span className={`vs-task-dot ${t.status.toLowerCase()}`} />
                    <span>
                      {t.title}
                      <small>
                        {taskLabel(t)} · 검증{" "}
                        {t.verification === "PASSED"
                          ? "통과"
                          : t.verification === "FAILED"
                            ? "실패"
                            : "미실행"}
                      </small>
                    </span>
                    <span>↗</span>
                  </button>
                ))}
              </div>
              {room === "director" && (
                <p>계획 승인과 결과 승인은 각각 필요합니다.</p>
              )}
              {room === "planning" && <p>실행 조건 확인 · 대기열</p>}
              {room === "qa" && <p>검증 통과 후에도 결과 승인을 기다립니다.</p>}
              {room === "academy" && (
                <>
                  <p>
                    활성 Skill{" "}
                    {model.skills.filter((s) => s.status === "ACTIVE").length} ·
                    검토 대기{" "}
                    {
                      model.skills.filter(
                        (s) =>
                          s.status === "DISCOVERED" || s.status === "REVIEWING",
                      ).length
                    }
                  </p>
                  <button
                    className="vs-link"
                    onClick={() => navigate("academy")}
                  >
                    Skill 검증 · 차단 작업 재개 ↗
                  </button>
                </>
              )}
              {room === "factory" && (
                <>
                  <p>
                    실행{" "}
                    {
                      model.runs.filter(
                        (r) =>
                          !["SUCCEEDED", "CANCELLED", "FAILED"].includes(
                            r.status,
                          ),
                      ).length
                    }{" "}
                    · 완료{" "}
                    {model.runs.filter((r) => r.status === "SUCCEEDED").length}
                  </p>
                  {model.runs
                    .filter(
                      (r) => !["SUCCEEDED", "CANCELLED"].includes(r.status),
                    )
                    .map((r) => (
                      <div className="vs-run" key={r.id}>
                        <strong>{r.definition.name}</strong>
                        {r.definition.nodes.map((n) => {
                          const step = r.steps[n.id];
                          const taskId = step?.attempts.at(-1);
                          return (
                            <button
                              key={n.id}
                              onClick={() =>
                                taskId
                                  ? openTask(taskId)
                                  : navigate("workflows")
                              }
                            >
                              <span>{n.title}</span>
                              <small>
                                {step?.status === "WAITING_DEPENDENCIES"
                                  ? "선행 작업 승인 대기"
                                  : step
                                    ? studioLabels[
                                        step.status as keyof typeof studioLabels
                                      ] || step.status
                                    : "대기"}{" "}
                                · 시도 {step?.attempts.length || 0}/
                                {n.maxAttempts}
                              </small>
                            </button>
                          );
                        })}
                      </div>
                    ))}
                  <button
                    className="vs-link"
                    onClick={() => navigate("workflows")}
                  >
                    의존성 · 승인 · 재시도 관리 ↗
                  </button>
                </>
              )}
              {room === "library" && (
                <>
                  <p>
                    등록 프로젝트{" "}
                    {
                      snapshot.projects.filter(
                        (p) => !project || p.id === project,
                      ).length
                    }{" "}
                    · 완료 작업{" "}
                    {model.tasks.filter((t) => t.status === "SUCCEEDED").length}
                  </p>
                  <button
                    className="vs-link"
                    onClick={() => navigate("projects")}
                  >
                    프로젝트 규칙과 저장소 ↗
                  </button>
                  <button className="vs-link" onClick={() => navigate("tasks")}>
                    작업 산출물과 변경 기록 ↗
                  </button>
                </>
              )}
              {room === "server" && (
                <>
                  <p>
                    {connected ? "이벤트 스트림 연결됨" : "이벤트 연결 복구 중"}{" "}
                    · 5초 간격 상태 동기화
                  </p>
                  <p>
                    검증 샌드박스{" "}
                    {snapshot.verificationSandbox ? "사용" : "미사용"}
                  </p>
                  {snapshot.adapters.map((a) => (
                    <p key={a.type}>
                      {a.type}:{" "}
                      {a.requirements.every((r) => r.available)
                        ? "준비됨"
                        : a.requirements
                            .filter((r) => !r.available)
                            .map((r) => r.name)
                            .join(", ") + " 확인 필요"}
                    </p>
                  ))}
                </>
              )}
              {room === "development" && occupants.length === 0 && (
                <p>현재 작업 중인 직원이 없습니다.</p>
              )}
            </section>
          );
        })}
      </div>
      {selected && (
        <aside className="vs-history" aria-label="직원 작업 이력">
          <header>
            <h3>
              {selected.agent.name} · {selected.agent.role}
            </h3>
            <button onClick={() => setEmployee("")}>이력 닫기</button>
          </header>
          <p>
            대표 상태{" "}
            {selected.current ? taskLabel(selected.current) : "대기 중"} · 최근
            작업 최대 20개
          </p>
          {selected.history.slice(0, 20).map((t) => (
            <button key={t.id} onClick={() => openTask(t.id)}>
              {t.title}
              <span>{taskLabel(t)}</span>
            </button>
          ))}
          {!selected.history.length && (
            <p>선택한 프로젝트의 작업 이력이 없습니다.</p>
          )}
        </aside>
      )}
      <footer className="vs-feed">
        <h3>최근 실제 이벤트</h3>
        {events
          .filter((e) => !project || model.tasks.some((t) => t.id === e.taskId))
          .slice(-5)
          .reverse()
          .map((e) => (
            <div key={e.id}>
              <time>{new Date(e.createdAt).toLocaleTimeString("ko-KR")}</time>
              <span>{e.type}</span>
              {e.taskId ? (
                <button onClick={() => openTask(e.taskId!)}>
                  {model.tasks.find((t) => t.id === e.taskId)?.title ||
                    "작업 열기"}{" "}
                  ↗
                </button>
              ) : (
                <span>{e.data.message || "스튜디오 업데이트"}</span>
              )}
            </div>
          ))}
      </footer>
    </section>
  );
}
