"use client";
import {useEffect,useRef,useState} from "react";
import {MessageSquare,Code2,FlaskConical,ClipboardCheck,Pause,Play} from "lucide-react";
import type {Task,StudioEvent} from "./types";
const stations=[{name:"총괄",place:"작전 테이블",icon:MessageSquare,color:"#7954a5"},{name:"개발",place:"제작 데스크",icon:Code2,color:"#3663b8"},{name:"검증 · 리뷰",place:"테스트 랩",icon:FlaskConical,color:"#287561"},{name:"나",place:"결과 테이블",icon:ClipboardCheck,color:"#ad641a"}];
export function phase(t?:Task){if(!t)return 0;if(t.status==="SUCCEEDED"||(t.status==="WAITING_APPROVAL"&&t.startedAt))return 3;if(t.status==="VERIFYING")return 2;if(t.status==="RUNNING"||t.status==="FAILED"||t.status==="CANCELLED")return 1;return 0;}
const eventNames:Record<string,string>={"task.running":"개발 담당이 구현을 시작했습니다","task.verifying":"구현 결과를 검증 담당에게 넘겼습니다","task.waiting_approval":"확인을 기다리고 있습니다","task.succeeded":"결과 승인이 기록됐습니다","task.failed":"작업을 멈추고 원인을 확인합니다","task.recovered":"보존한 변경에서 작업을 이어갑니다","steering.received":"추가 지시를 접수했습니다","steering.applied":"추가 지시를 실행에 반영했습니다","delivery.updated":"결과 반영 상태가 바뀌었습니다"};
export default function ProjectRoom({tasks,events,connected,thinking,conversationState,openTask,focusConversation}:{tasks:Task[];events:StudioEvent[];connected:boolean;thinking:boolean;conversationState?:string;openTask:(id:string)=>void;focusConversation:()=>void}){
 const current=tasks.find(t=>!["SUCCEEDED","CANCELLED"].includes(t.status))||tasks.at(-1);const stage=thinking?0:phase(current);
 const [selected,setSelected]=useState<number|null>(null),[paused,setPaused]=useState(false),[handoff,setHandoff]=useState("");const previous=useRef(stage);
 useEffect(()=>{if(previous.current!==stage){setHandoff(`${stations[previous.current].name} → ${stations[stage].name}`);previous.current=stage;const timer=setTimeout(()=>setHandoff(""),4500);return()=>clearTimeout(timer);}},[stage]);
 const selecting=!!current?.routingPending;
 const blocked=!selecting&&!!current&&["FAILED","BLOCKED","CANCELLED"].includes(current.status);
 const active=connected&&!paused&&!blocked&&(thinking||selecting||!!current&&["RUNNING","VERIFYING","ANALYZING"].includes(current.status));
 const taskIds=new Set(tasks.map(t=>t.id));const recent=events.filter(e=>e.taskId&&taskIds.has(e.taskId)&&eventNames[e.type]).slice(-5).reverse();
 const status=blocked?"중단됨 · 변경 내용은 보존돼 있어요":thinking?"총괄이 요청을 살펴보고 있어요":selecting?"총괄이 작업에 맞는 모델과 추론 수준을 고르고 있어요":conversationState==="PLAN_READY"?"진행할 계획을 확인하고 맡겨 주세요":conversationState==="STOPPED"?"대화를 중지했어요 · 기록은 보존돼 있어요":!current?"첫 요청을 기다리고 있어요":stage===3?current.status==="SUCCEEDED"?"결과 승인됨 · 반영 상태는 결과에서 확인":"검증을 마쳤어요. 결과를 확인해 주세요":stage===2?current.reviewStatus==="RUNNING"?"독립 리뷰 담당이 변경을 확인하고 있어요":"테스트로 동작을 확인하고 있어요":stage===1?"개발 담당이 코드를 만들고 있어요":"담당자와 실행 조건을 준비하고 있어요";
 const picked=selected??stage;
 return <section className={`project-room ${active?"room-working":""} ${!connected?"room-stale":""}`} aria-label="프로젝트 작업실"><header><div><h3>우리 프로젝트 작업실</h3><p>누가 무엇을 기다리는지 함께 봐요.</p></div><button className="room-motion" aria-label={paused?"작업실 움직임 켜기":"작업실 움직임 끄기"} onClick={()=>setPaused(!paused)}>{paused?<Play size={16}/>:<Pause size={16}/>}</button></header>
 <div className="room-stage"><div className="room-window"><i/><i/><i/></div><div className="room-plant" aria-hidden="true">♧</div><div className="room-route" aria-hidden="true"><span/><span/><span/></div>
 {stations.map((s,i)=>{const Icon=s.icon;return <button key={s.name} className={`room-station station-${i} ${stage===i?"at-work":""} ${picked===i?"picked":""}`} style={{"--station-color":s.color} as React.CSSProperties} aria-pressed={picked===i} aria-label={`${s.name} · ${s.place}${stage===i?" · 현재 단계":""}`} onClick={()=>{setSelected(i);if(i===0)focusConversation();}}><span className="room-bubble">{stage===i?(blocked?"잠깐 멈춤":i===3?(current?.status==="SUCCEEDED"?"승인 완료":"확인해 주세요"):thinking?"살펴보는 중":active?"진행 중":"대기 중"):"차례를 기다려요"}</span><span className="room-bot" aria-hidden="true"><i className="bot-antenna"/><i className="bot-face"><b/><b/></i><i className="bot-body"><Icon size={14}/></i></span><span className="room-desk" aria-hidden="true"/><strong>{s.name}</strong><small>{s.place}</small></button>;})}
 {handoff&&connected&&!paused&&<span className="room-handoff" role="status">전달: {handoff}</span>}</div>
 <div className={`room-status ${blocked?"needs-attention":""}`} role="status"><span className="room-status-dot"/>{!connected&&"연결 확인 중 · 마지막 확인: "}{status}</div>
 <div className="room-inspector"><strong>{stations[picked].name}</strong><p>{picked===0?"요청을 이해하고 담당자에게 전달합니다. 아래 대화로 방향을 바꿀 수 있어요.":picked===1?"선택한 모델이 실제 저장소의 별도 작업 공간에서 구현합니다.":picked===2?"테스트와 구현 호출에서 분리된 리뷰로 변경을 확인합니다.":"결과를 읽고 수정 요청 또는 반영 승인을 합니다."}</p>{current&&picked>0&&<><span className="room-current-task">{current.title}</span>{picked===stage&&current.model&&<small>{current.adapter} · {current.model}{current.reasoning?` · ${current.reasoning}`:""}</small>}<button className="room-detail" onClick={()=>openTask(current.id)}>이 작업의 결과와 근거 보기</button></>}</div>
 {recent.length>0&&<ol className="room-events" aria-label="실제 작업 전달 기록">{recent.map(e=><li key={e.id}><time>{new Date(e.createdAt).toLocaleTimeString("ko-KR",{hour:"2-digit",minute:"2-digit"})}</time>{eventNames[e.type]}</li>)}</ol>}
 <p className="room-footnote">직무 간 흐름을 보여주는 공간입니다. 각 캐릭터가 별도 AI 프로세스를 뜻하지는 않습니다.</p>
 </section>;
}
