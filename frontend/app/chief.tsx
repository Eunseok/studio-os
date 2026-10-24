"use client";
import {useEffect, useState, useCallback, useRef} from "react";
import ResultCard from "./result-card";
import type {ConversationContext} from "./project-workspace";
import MessageContent from "./message-content";
import ExecutionDetails, {type ExecutionSettings} from "./execution-settings";
import type {Snapshot} from "./types";
type Question={id:string;text:string;options:string[];required:boolean};
type Instruction={requestKey:string;text:string;status:string;taskId?:string;revision:number};
type Conversation={instructions?:Instruction[];steeringKey?:string;id:string;projectId:string;state:string;requestKey?:string;runId?:string;error?:string;responseExecution?:ExecutionSettings;answers?:Record<string,string>;messages:{role:string;text:string;at:string;execution?:ExecutionSettings;questionId?:string}[];plan?:{response:string;questions:(string|Question)[];steps:{title:string;instruction:string}[]}};
type Submission={conversationId?:string;projectId:string;message:string;requestKey:string};
const states:Record<string,string>={THINKING:"총괄이 프로젝트를 살펴보고 있습니다",PLAN_READY:"진행할 내용을 확인해 주세요",RUNNING:"총괄이 담당자들과 진행하고 있습니다",WAITING_RESULT:"결과를 확인해 주세요",STEERING:"추가 지시를 접수했습니다 · 적용 확인 대기",COMPLETE:"완료",NEEDS_HELP:"확인이 필요합니다",STOPPED:"중지됨",DISCUSSING:"함께 방향 정하기"};
async function request<T>(path:string,body?:unknown):Promise<T>{const r=await fetch(`/api${path}`,body===undefined?{cache:"no-store"}:{method:"POST",headers:{"Content-Type":"application/json","X-Studio-Client":"1"},body:JSON.stringify(body)});const v=await r.json();if(!r.ok)throw new Error(v.message||"요청을 완료하지 못했습니다");return v;}
function QuestionAnswer({question,answer,disabled,onAnswer}:{question:Question;answer?:string;disabled:boolean;onAnswer:(value:string)=>Promise<void>}){
 const [draft,setDraft]=useState(answer||"");
 return <form className="chief-question" onSubmit={e=>{e.preventDefault();void onAnswer(draft);}}>
  <MessageContent text={question.text}/><small>{question.required?"필수 답변":"선택 답변"}</small>
  {question.options.length>0&&<div className="chief-question-options" role="group" aria-label={question.text}>{question.options.map(option=><button type="button" className="button" key={option} aria-pressed={answer===option} disabled={disabled} onClick={()=>{setDraft(option);void onAnswer(option);}}>{option}</button>)}</div>}
  <label>직접 입력<textarea aria-label={`${question.text} · 직접 입력`} rows={2} maxLength={1000} value={draft} disabled={disabled} onChange={e=>setDraft(e.target.value)}/></label>
  <button className="button" disabled={disabled||!draft.trim()}>답변 저장</button>
  {answer&&<p className="chief-question-answer">저장된 답변: {answer}</p>}
 </form>;
}
export default function Chief({snapshot,refresh,openTask,openDocuments,addProject,projectId,focusId,onConversation}:{projectId?:string;focusId?:string;onConversation?:(c:ConversationContext)=>void;snapshot:Snapshot;refresh:()=>Promise<void>;openTask:(id:string)=>void;openDocuments:()=>void;addProject:()=>void}){
 const [conversations,setConversations]=useState<Conversation[]>([]),[selected,setSelected]=useState(""),[project,setProject]=useState(projectId||snapshot.projects[0]?.id||""),[text,setText]=useState(""),[busy,setBusy]=useState(false),[error,setError]=useState("");
 const loadedInitial=useRef(false);
 useEffect(()=>{if(focusId)setSelected(focusId);},[focusId]);
 const actionPending=useRef(false),loadRevision=useRef(0),submission=useRef<Submission|null>(null),retryRequest=useRef<{conversationId:string;expectedRequestKey:string;requestKey:string}|null>(null);
 const load=useCallback(async()=>{if(actionPending.current)return;const revision=++loadRevision.current;const result=await request<Conversation[]>("/chief");if(revision===loadRevision.current){const visible=projectId?result.filter(c=>c.projectId===projectId):result;setConversations(visible);if(!loadedInitial.current){loadedInitial.current=true;const saved=localStorage.getItem("studio.chief."+(projectId||"all"));setSelected(visible.some(c=>c.id===saved)?saved!:visible[0]?.id||"");}}},[projectId]);
 useEffect(()=>{void load().catch(e=>setError(e.message));const timer=setInterval(()=>void load().catch(e=>setError("대화 연결을 다시 확인하고 있습니다: "+e.message)),2000);return()=>{clearInterval(timer);loadRevision.current++;};},[load]);
 const c=conversations.find(c=>c.id===selected),run=snapshot.workflowRuns.find(r=>r.id===c?.runId),tasks=run?Object.values(run.steps).flatMap(s=>s.attempts.length?[snapshot.tasks.find(t=>t.id===s.attempts[s.attempts.length-1])].filter(t=>!!t):[]):[];
 useEffect(()=>{onConversation?.({id:c?.id,runId:c?.runId,state:c?.state});if(c)localStorage.setItem("studio.chief."+(projectId||"all"),c.id);},[c?.id,c?.runId,c?.state,projectId,onConversation]);
 const questions:Question[]=(c?.plan?.questions||[]).map((q,i)=>typeof q==="string"?{id:`legacy-question-${i+1}`,text:q,options:[],required:true}:q);
 const missingRequired=questions.some(q=>q.required&&!c?.answers?.[q.id]?.trim());
 const questionRevision=c?.messages.filter(m=>m.role==="assistant").at(-1)?.at||"";
 const activeConversation=!!c&&["THINKING","RUNNING","WAITING_RESULT","STEERING"].includes(c.state);
 const instructionStates:Record<string,string>={RECEIVED:"접수 · 기존 호출 종료 대기",WAITING_APPROVAL:"반영 대기 · 적용 승인 필요",PENDING:"반영 대기",APPLIED:"반영 완료 · 새 호출에 전달됨",CANCELLED:"중지됨",BLOCKED:"반영 중단 · 환경 확인 필요"};
 const instructionReady=c?.state==="STEERING"&&!!c.instructions?.some(i=>i.status==="WAITING_APPROVAL")&&!c.instructions?.some(i=>i.status==="RECEIVED");
 async function act(path:string,body:unknown={}):Promise<Conversation|undefined>{if(actionPending.current)return;actionPending.current=true;loadRevision.current++;setBusy(true);setError("");try{const result=await request<Conversation>(path,body);loadRevision.current++;setConversations(items=>[result,...items.filter(item=>item.id!==result.id)]);setSelected(result.id);void refresh().catch(e=>setError(`상태 갱신 실패: ${(e as Error).message}`));return result;}catch(e){setError((e as Error).message);}finally{actionPending.current=false;setBusy(false);}}
 async function sendMessage(){
  if(actionPending.current||!text.trim())return;
  const projectId=c?.projectId||project;
  const previous=submission.current;
  const pending=previous&&previous.conversationId===c?.id&&previous.projectId===projectId&&previous.message===text?previous:{conversationId:c?.id,projectId,message:text,requestKey:crypto.randomUUID()};
  submission.current=pending;
  const result=await act("/chief",pending);
  if(result){submission.current=null;setText(value=>value===pending.message?"":value);}
 }
 async function retryResponse(){
  if(!c?.requestKey||actionPending.current)return;
  const previous=retryRequest.current;
  const pending=previous?.conversationId===c.id&&previous.expectedRequestKey===c.requestKey?previous:{conversationId:c.id,expectedRequestKey:c.requestKey,requestKey:crypto.randomUUID()};
  retryRequest.current=pending;
  if(await act(`/chief/${c.id}/retry`,{expectedRequestKey:pending.expectedRequestKey,requestKey:pending.requestKey}))retryRequest.current=null;
 }
 return <div className="chief-layout"><section className="chief-conversation">
  <div className="chief-intro"><span className="chief-eyebrow">프로젝트 총괄</span><h2>이 프로젝트에서 무엇을 바꿀까요?</h2><p>방향을 함께 정하면 담당자 배정부터 구현·검증·리뷰까지 이어서 진행합니다.</p></div>
  <div className="chief-context">{!projectId&&<label>현재 프로젝트<select aria-label="대화할 프로젝트" value={c?.projectId||project} disabled={!!c||busy} onChange={e=>setProject(e.target.value)}>{snapshot.projects.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label>}{!projectId&&<button className="button" onClick={addProject}>프로젝트 연결</button>}<button className="button" disabled={busy} onClick={()=>{setSelected("");setText("");submission.current=null;localStorage.removeItem("studio.chief."+(projectId||"all"));}}>새 대화</button></div>
  {!c&&<div className="chief-starters"><p>현재 프로젝트에서 바꾸고 싶은 것부터 시작하세요.</p>{["이 프로젝트에서 개선할 점을 함께 정리하고 싶어.","프로젝트 문서가 너무 많아. 내용을 살펴보고 사람이 이해할 수 있게 정리하는 방법을 제안해줘.","처음 사용하는 사람이 이해하기 어려운 부분을 찾아 작은 개선 하나를 제안해줘."].map(t=><button key={t} onClick={()=>setText(t)}>{t}</button>)}</div>}
  <div className="chief-messages" aria-live="polite">{c?.messages.map((m,i)=><article className={`chief-message ${m.role}`} key={i}><strong>{m.role==="user"?"나":"프로젝트 총괄"}</strong><MessageContent text={m.text}/>{m.role==="assistant"&&m.execution&&<ExecutionDetails label="총괄 응답" settings={m.execution}/>}</article>)}</div>
  {c&&<ExecutionDetails label="현재 총괄 응답" settings={c.responseExecution} pending={c.state==="THINKING"&&!c.responseExecution}/>}
  {c&&<p className="chief-state" role="status">{states[c.state]||c.state}</p>}
  {c?.instructions&&c.instructions.length>0&&<div className="chief-plan" aria-live="polite"><h3>추가 지시</h3>{c.instructions.map(i=><div key={i.requestKey}><MessageContent text={i.text}/><p role="status">{instructionStates[i.status]||i.status}</p></div>)}{c.state==="STEERING"&&<><p>기존 변경을 보존했습니다. 추가 지시로 넓어지는 범위를 포함해 내용을 확인하고 적용을 승인해 주세요. 재개 후 테스트와 독립 리뷰를 다시 진행합니다.</p><button className="button primary" disabled={busy||!instructionReady} onClick={()=>act(`/chief/${c.id}/instructions/approve`,{requestKey:c.steeringKey})}>추가 지시 적용 승인</button></>}</div>}
  {c&&questions.map(q=><QuestionAnswer key={`${c.id}:${questionRevision}:${q.id}`} question={q} answer={c.answers?.[q.id]} disabled={busy||!["DISCUSSING","PLAN_READY"].includes(c.state)} onAnswer={async value=>{await act(`/chief/${c.id}/answer`,{questionId:q.id,answer:value});}}/>)}
  {missingRequired&&<p role="status">실행을 승인하려면 필수 질문에 먼저 답변해 주세요.</p>}
  {c?.state==="PLAN_READY"&&<div className="chief-plan"><h3>이렇게 진행하겠습니다</h3><ol>{c.plan?.steps.map((s,i)=><li key={i}><MessageContent text={s.title}/></li>)}</ol><p>계획 승인 후 모델 선택·실행·테스트·독립 리뷰와 최대 2회 시도를 맡깁니다. 환경 문제는 멈추고 알려드립니다.</p><button className="button primary" disabled={busy||missingRequired} onClick={()=>act(`/chief/${c.id}/start`)}>이 계획으로 맡기고 진행</button><details><summary>계획 자세히 보기</summary>{c.plan?.steps.map((s,i)=><MessageContent key={i} text={s.instruction}/>)}</details></div>}
  {c&&["PLAN_READY","DISCUSSING"].includes(c.state)&&!!c.plan?.steps.length&&<p>승인 전에는 아래 대화로 단계 삭제·수정을 요청할 수 있습니다.</p>}
  {run&&<div className="chief-progress"><h3>진행 상황</h3>{tasks.map(t=><div key={t.id}><span>{t.title}</span><span>{t.steeringPending?(t.steeringStopped?"추가 지시 적용 승인 대기":"기존 호출 종료 대기"):t.routingPending?"담당 모델 판단 중":t.reviewStatus==="RUNNING"?"독립 리뷰 중":t.status==="WAITING_APPROVAL"&&t.startedAt?"검증·리뷰 완료":t.status==="SUCCEEDED"?"완료":t.status==="FAILED"?"중단됨 · 복구 가능":t.status==="VERIFYING"?"테스트 중":t.status==="RUNNING"?"구현 중":"준비 중"}</span><ExecutionDetails label="구현 작업 · 실행에 전달한 설정" settings={t.execution}/>{t.steeringCapability&&<small>{t.steeringCapability}</small>}{t.steeringPending&&<p role="status">{t.message}</p>}{!t.execution&&!t.startedAt&&<ExecutionDetails label="구현 작업 · 선택 계획" pending={!!t.routingPending} settings={t.routingPending||!t.model?null:{tool:t.adapter,model:t.model,reasoning:t.reasoning}}/>}<button onClick={()=>openTask(t.id)}>변경·근거 보기</button></div>)}</div>}
  {c&&["WAITING_RESULT","COMPLETE"].includes(c.state)&&tasks.length>0&&<ResultCard task={tasks[tasks.length-1]!} conversationId={c.id} canApprove refresh={refresh}/>}
  {c?.runId&&["NEEDS_HELP","STOPPED"].includes(c.state)&&<div className="chief-recovery"><strong>멈춘 부분부터 이어갈 수 있어요.</strong><p>기존 변경을 보존한 채 남은 작업을 진행하고 검증·리뷰를 다시 합니다.</p><button className="button primary" disabled={busy} onClick={()=>act(`/chief/${c.id}/recover`)}>보존한 코드에서 이어가기</button></div>}
  {c&&["THINKING","RUNNING","WAITING_RESULT","STEERING"].includes(c.state)&&<button className="button" disabled={busy} onClick={()=>act(`/chief/${c.id}/stop`,{requestKey:c.requestKey})}>진행 중지</button>}
  {c&&!c.runId&&c.requestKey&&["STOPPED","NEEDS_HELP"].includes(c.state)&&<button className="button" disabled={busy} onClick={()=>void retryResponse()}>마지막 요청 다시 시도</button>}
  {c?.error&&<p role="alert" className="form-error">{c.error}</p>}
  {error&&<p role="alert" className="form-error">{error}</p>}
  <form className="chief-composer" onSubmit={e=>{e.preventDefault();void sendMessage();}}><label htmlFor="chief-message">총괄에게 이야기하기</label><textarea id="chief-message" value={text} onChange={e=>setText(e.target.value)} required maxLength={6000} rows={4} placeholder={activeConversation?"진행 중에도 방향을 바꾸거나 추가 지시를 보낼 수 있습니다.":"불편한 점, 만들고 싶은 것, 아직 정리되지 않은 생각도 괜찮습니다."} disabled={busy}/><button className="button primary" disabled={busy||!(c?.projectId||project)||!text.trim()}>{activeConversation?"추가 지시 보내기":"보내기"}</button></form>
 </section><aside className="chief-side"><section><h3>AI와 함께 보는 프로젝트 문서</h3><p>목표·결정·인수인계를 AI와 같은 문서로 확인합니다. 문서는 스튜디오에 저장됩니다.</p><button className="button" onClick={openDocuments}>공유 문서 열기</button></section><section><h3>지난 대화</h3>{conversations.length===0?<p>첫 대화를 시작해 보세요.</p>:conversations.map(item=><button className="chief-history" key={item.id} disabled={busy} onClick={()=>{setSelected(item.id);setProject(item.projectId);}}>{item.messages.find(m=>m.role==="user")?.text.slice(0,70)}<small>{states[item.state]||item.state}</small></button>)}</section></aside></div>;
}
