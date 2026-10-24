"use client";
import {useEffect,useState} from "react";
import Chief from "./chief";
import SharedDocuments from "./shared-documents";
import GithubBoard from "./github-board";
import ProjectRoom from "./project-room";
import ResultCard from "./result-card";
import type {Snapshot,StudioEvent} from "./types";
export type ConversationContext={id?:string;runId?:string;state?:string};
export default function ProjectWorkspace({snapshot,events,connected,refresh,addProject}:{snapshot:Snapshot;events:StudioEvent[];connected:boolean;refresh:()=>Promise<void>;addProject:()=>void}){
 const [projectId,setProjectId]=useState(snapshot.projects[0]?.id||"");const [tab,setTab]=useState("conversation");const [context,setContext]=useState<ConversationContext>({});const [resultId,setResultId]=useState("");const [focusId,setFocusId]=useState("");const [error,setError]=useState("");const [recovering,setRecovering]=useState(false);
 useEffect(()=>{const saved=localStorage.getItem("studio.workspace.project");if(snapshot.projects.some(p=>p.id===saved))setProjectId(saved!);},[]);
 const project=snapshot.projects.find(p=>p.id===projectId)||snapshot.projects[0];
 const scoped={...snapshot,projects:project?[project]:[],tasks:snapshot.tasks.filter(t=>t.projectId===project?.id),workflowRuns:snapshot.workflowRuns.filter(r=>r.project.id===project?.id)};
 const run=scoped.workflowRuns.find(r=>r.id===context.runId)||scoped.workflowRuns.find(r=>r.status==="RUNNING")||scoped.workflowRuns.at(0);
 const roomTasks=run?Object.values(run.steps).flatMap(s=>{const t=scoped.tasks.find(t=>t.id===s.attempts.at(-1));return t?[t]:[];}):scoped.tasks.filter(t=>!t.improvementAnalysis).slice(0,5);
 const result=scoped.tasks.find(t=>t.id===resultId);const interrupted=scoped.workflowRuns.filter(r=>["FAILED","CANCELLED"].includes(r.status)&&Object.values(r.steps).some(s=>s.attempts.length));
 function openResult(id:string){setResultId(id);setTab("results");}
 async function recover(runId:string){setRecovering(true);setError("");try{const r=await fetch(`/api/chief/recover-run/${runId}`,{method:"POST",headers:{"X-Studio-Client":"1"}});const c=await r.json();if(!r.ok)throw new Error(c.message);setFocusId(c.id);setContext(c);setTab("conversation");await refresh();}catch(e){setError((e as Error).message);}finally{setRecovering(false);}}
 if(!project)return <section className="workspace-empty"><h2>함께 일할 프로젝트부터 연결하세요.</h2><p>Git 프로젝트 폴더 하나를 선택하면 총괄과 대화하며 작업을 시작할 수 있습니다.</p><button className="button primary" onClick={addProject}>프로젝트 연결</button></section>;
 return <section className="project-workspace" aria-label="프로젝트 공간"><header className="workspace-header"><div><label htmlFor="workspace-project">지금 함께 일하는 프로젝트</label><select id="workspace-project" value={project.id} onChange={e=>{setProjectId(e.target.value);localStorage.setItem("studio.workspace.project",e.target.value);setContext({});setResultId("");setFocusId("");setTab("conversation");}}>{snapshot.projects.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select><p>대화하고, 함께 만들고, 여기서 결과를 확인하세요.</p></div><button className="button" onClick={addProject}>다른 프로젝트 연결</button></header>
 <nav className="workspace-tabs" aria-label="프로젝트 공간 보기">{[["conversation","총괄과 대화"],["documents","공유 지식"],["results","작업과 결과"],["history","GitHub 기록"]].map(([key,name])=><button key={key} aria-current={tab===key?"page":undefined} onClick={()=>setTab(key)}>{name}</button>)}</nav>
 {error&&<p role="alert">{error}</p>}
 <div className="workspace-body"><main className="workspace-main">
 <div hidden={tab!=="conversation"}><Chief key={project.id} projectId={project.id} focusId={focusId} onConversation={setContext} snapshot={scoped} refresh={refresh} openTask={openResult} openDocuments={()=>setTab("documents")} addProject={addProject}/>
 {interrupted.length>0&&<details className="workspace-recovery"><summary>이어갈 수 있는 이전 작업</summary>{interrupted.map(r=><article key={r.id}><strong>{r.definition.name}</strong><p>앞선 결과와 수정 중인 코드를 보존했습니다. 남은 단계를 복구하고 다시 검증합니다.</p><button className="button" disabled={recovering} onClick={()=>recover(r.id)}>{recovering?"복구 준비 중…":"보존한 코드에서 이어가기"}</button></article>)}</details>}</div>
 {tab==="documents"&&<SharedDocuments key={project.id} snapshot={scoped}/>}
 {tab==="results"&&<section className="workspace-results"><h2>진행 중인 일과 결과</h2>{scoped.tasks.length===0?<p>총괄에게 첫 요청을 보내면 진행 상황이 여기에 나타납니다.</p>:<div className="workspace-task-list">{scoped.tasks.filter(t=>!t.improvementAnalysis).map(t=><button key={t.id} aria-pressed={resultId===t.id} onClick={()=>setResultId(t.id)}><strong>{t.title}</strong><span>{t.status==="FAILED"?"중단됨":t.status==="SUCCEEDED"?"결과 승인됨":t.status==="WAITING_APPROVAL"&&t.startedAt?"결과 확인 필요":t.status==="RUNNING"?"구현 중":t.status==="VERIFYING"?"검증 중":"준비 중"}</span></button>)}</div>}{result&&<ResultCard task={result} refresh={refresh}/>}<button className="button" onClick={()=>setTab("conversation")}>총괄과 이야기하기</button></section>}
 {tab==="history"&&<GithubBoard projectId={project.id}/>}
 </main><aside className="workspace-team"><ProjectRoom tasks={roomTasks} events={events} connected={connected} thinking={context.state==="THINKING"} openTask={openResult} focusConversation={()=>{setTab("conversation");requestAnimationFrame(()=>document.getElementById("chief-message")?.focus());}}/></aside></div>
 </section>;
}
