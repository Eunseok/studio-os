"use client";
import { useEffect, useState } from "react";
import type { Task } from "./types";
type Entry = {
  provider: string;
  model: string;
  reasoningOptions: string[];
  cliReady: boolean;
};
export default function ModelSettings({
  task,
  refresh,
}: {
  task: Task;
  refresh: () => Promise<void>;
}) {
  const [models, setModels] = useState<Entry[]>([]),
    [value, setValue] = useState(`${task.adapter}|${task.model}`),
    [effort, setEffort] = useState(task.reasoning || "medium"),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    let closed = false;
    fetch("/api/models")
      .then((r) => r.json())
      .then((v) => {
        if (!closed) setModels(v);
      })
      .catch((e) => {
        if (!closed) setError(e.message);
      });
    return () => {
      closed = true;
    };
  }, []);
  const chosen = models.find((m) => `${m.provider}|${m.model}` === value);
  return (
    <details className="model-settings">
      <summary>모델·추론 직접 변경</summary>
      <p>
        현재 등록된 모델만 선택할 수 있습니다. CLI 준비 상태와 계정의 해당 모델
        이용 권한은 다릅니다. 실제 비용·잔여량은 확인되지 않았습니다.
      </p>
      <label>
        요청 모델
        <select value={value} onChange={(e) => setValue(e.target.value)}>
          {models.map((m) => (
            <option
              key={`${m.provider}|${m.model}`}
              value={`${m.provider}|${m.model}`}
            >
              {m.provider} / {m.model}
              {m.cliReady ? "" : " · CLI 확인 필요"}
            </option>
          ))}
        </select>
      </label>
      <label>
        요청 추론 수준
        <select value={effort} onChange={(e) => setEffort(e.target.value)}>
          {(chosen?.reasoningOptions || ["low", "medium", "high"]).map((e) => (
            <option key={e}>{e}</option>
          ))}
        </select>
      </label>
      <button
        disabled={busy || !chosen}
        onClick={async () => {
          setBusy(true);
          setError("");
          try {
            const r = await fetch(`/api/models/tasks/${task.id}`, {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                "X-Studio-Client": "1",
              },
              body: JSON.stringify({
                provider: chosen?.provider,
                model: chosen?.model,
                reasoning: effort,
              }),
            });
            if (!r.ok) throw new Error((await r.json()).message);
            await refresh();
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        실행 계획에 적용
      </button>
      {error && <p role="alert">{error}</p>}
    </details>
  );
}
