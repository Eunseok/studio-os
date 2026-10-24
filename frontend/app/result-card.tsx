"use client";
import {useEffect,useState} from "react";
import type {Task} from "./types";
import MessageContent from "./message-content";
type Delivery={status:string;message:string;prUrl?:string;commit?:string};
export default function ResultCard({task,conversationId,canApprove,refresh}:{task:Task;conversationId?:string;canApprove?:boolean;refresh:()=>Promise<void>}){
 const [diff,setDiff]=useState(""),[error,setError]=useState(""),[busy,setBusy]=useState(false),[delivery,setDelivery]=useState<Delivery>();
 useEffect(()=>{let alive=true;setDiff("");fetch(`/api/tasks/${task.id}/changes`).then(async r=>{const d=await r.json();if(!r.ok)throw new Error(d.message||"변경을 읽지 못했습니다");if(alive)setDiff(d.diff);}).catch(e=>{if(alive)setError(e.message);});return()=>{alive=false;};},[task.id,task.updatedAt]);
 useEffect(()=>{if(!conversationId)return;let alive=true;const load=async()=>{try{const r=await fetch(`/api/chief/${conversationId}/delivery`);if(!r.ok)return;const d=await r.json();if(alive)setDelivery(d);}catch{}};void load();const timer=setInterval(load,3000);return()=>{alive=false;clearInterval(timer);};},[conversationId]);
 async function deliver(retry=false){if(!conversationId)return;setBusy(true);setError("");try{const r=await fetch(`/api/chief/${conversationId}/delivery${retry?"/retry":""}`,{method:"POST",headers:{"X-Studio-Client":"1"}});const d=await r.json();if(!r.ok)throw new Error(d.message||"반영 요청 실패");setDelivery(d);await refresh();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 const files=[...new Set([...diff.matchAll(/^diff --git a\/(.*?) b\/(.*)$/gm)].map(m=>m[2]))];
 const sent=delivery&&delivery.status!=="NONE";
 return <section className="result-card" aria-label="작업 결과"><header><span className="result-symbol">✓</span><div><h3>{canApprove&&!task.appliedCommit?"직접 확인할 결과가 도착했어요":"작업 결과와 근거"}</h3><p>{task.title}</p></div></header>
 <div className="result-checks"><span data-ok={task.verification==="PASSED"}>테스트 {task.verification==="PASSED"?"통과":task.verification==="FAILED"?"실패":"아직 확인 전"}</span><span data-ok={task.reviewStatus==="PASSED"}>독립 리뷰 {task.reviewStatus==="PASSED"?"통과":task.reviewStatus==="RUNNING"?"진행 중":task.reviewStatus==="CHANGES_REQUIRED"?"수정 필요":task.reviewStatus==="FAILED"?"실패":"아직 확인 전"}</span><span>반영 {(delivery?.status==="APPLIED"||task.appliedCommit)?"완료":"아직 안 됨"}</span></div>
 <p>{task.message}</p>{task.review?.summary&&<details><summary>독립 리뷰 상세 보기</summary><MessageContent text={task.review.summary}/></details>}
 {files.length>0&&<details><summary>변경한 파일 {files.length}개</summary><ul>{files.map(f=><li key={f}>{f}</li>)}</ul></details>}
 <details><summary>실제 코드 변경 보기</summary><pre className="result-diff">{diff||"변경을 확인하고 있습니다."}</pre></details>
 {canApprove&&!sent&&!task.appliedCommit&&<div className="result-decision"><p>승인하면 검증한 코드의 반영까지 총괄에게 맡깁니다. GitHub가 연결돼 있으면 PR 생성·검사 확인·머지까지 진행합니다. 실행 중인 앱의 재시작은 포함하지 않습니다.</p><button className="button primary" disabled={busy||task.verification!=="PASSED"||task.reviewStatus!=="PASSED"} onClick={()=>deliver()}>결과 승인하고 반영</button><p>바꿀 부분이 있으면 아래 대화에 적어 주세요. 승인 전에 계속 수정할 수 있습니다.</p></div>}
 {sent&&<div className="delivery-status" role="status"><strong>{delivery.status==="APPLIED"?"프로젝트에 반영했습니다":delivery.status==="BLOCKED"?"반영에 확인이 필요합니다":"반영을 진행하고 있습니다"}</strong><p>{delivery.message}</p>{delivery.prUrl&&<a href={delivery.prUrl} target="_blank" rel="noreferrer">GitHub 변경 기록 보기</a>}{delivery.status==="BLOCKED"&&<button className="button" disabled={busy} onClick={()=>deliver(true)}>문제 해결 후 반영 이어가기</button>}</div>}
 {error&&<p role="alert">{error}</p>}
 </section>;
}
