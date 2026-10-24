export type ExecutionSettings = {tool?: string | null; model?: string | null; reasoning?: string | null};

export default function ExecutionDetails({label, settings, pending = false}: {label: string; settings?: ExecutionSettings | null; pending?: boolean}) {
  return <p className="execution-settings"><strong>{label}</strong>{" · "}{pending ? "선택 중" : settings ? `실행 도구 ${settings.tool || "알 수 없음"} · 전달 모델 ${settings.model || "알 수 없음"} · 전달 추론 ${settings.reasoning || "알 수 없음"}` : "실행 설정 기록 없음"}</p>;
}
