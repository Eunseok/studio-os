"use client";
import {useCallback,useEffect,useState} from "react";
type Item={id:string;title:string;column:string;issueUrl?:string;prUrl?:string;prState?:string;head?:string;error?:string;runtimeStatus?:string;message?:string;canPublish?:boolean};
type Binding={repo:string;boardUrl:string;error?:string;lastSync?:string};
type Board={bindings:Binding[];items:Item[];syncing:boolean};
const columns=[ ["Todo","남은 작업"],["In Progress","진행 중"],["In Review","검토 대기"],["Done","머지 완료"] ];
export default function GithubBoard({projectId}:{projectId:string}){
 const [board,setBoard]=useState<Board>();const [error,setError]=useState("");const [busy,setBusy]=useState(false);
 const [repo,setRepo]=useState(""),[owner,setOwner]=useState(""),[number,setNumber]=useState("");
 const base=`/api/projects/${projectId}/github`;
 const load=useCallback(async()=>{const r=await fetch(base);if(!r.ok)throw new Error("GitHub 연결 정보를 읽지 못했습니다");setBoard(await r.json());},[base]);
 useEffect(()=>{let stopped=false;const read=()=>{if(!stopped)void load().catch(e=>setError(e.message));};read();const timer=setInterval(read,10000);return()=>{stopped=true;clearInterval(timer);};},[load]);
 async function action(path:string,body:unknown={}){setBusy(true);setError("");try{const r=await fetch(base+path,{method:"POST",headers:{"Content-Type":"application/json","X-Studio-Client":"1"},body:JSON.stringify(body)});const d=await r.json();if(!r.ok)throw new Error(d.message||"GitHub 요청 실패");await load();if(path==="/pull")setError(d.message);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 const binding=board?.bindings[0];
 return <section className="github-board"><h3>GitHub 작업 보드</h3>{error&&<p role="alert">{error}</p>}{binding?<>
 <p><a href={binding.boardUrl} target="_blank" rel="noreferrer">Projects에서 보기 ↗</a> · {binding.repo}</p>
 <p>총괄이 만든 새 작업은 자동으로 이슈와 연결됩니다. 결과 승인 후 PR을 만들고, 머지되면 완료로 이동합니다.</p>
 <div className="github-actions"><button className="button" disabled={busy||board?.syncing} onClick={()=>action("/sync")}>{board?.syncing?"동기화 중…":"GitHub 동기화"}</button><button className="button" disabled={busy} onClick={()=>action("/pull")}>머지된 코드 가져오기</button></div>
 <small>코드를 가져와도 실행 중인 앱은 바뀌지 않습니다. 평소 실행 스크립트로 다시 시작하세요.</small>
 {binding.error&&<p role="alert">{binding.error}</p>}
 <div className="github-columns">{columns.map(([key,label])=><section key={key}><h4>{label}</h4>{board?.items.filter(i=>i.column===key).map(i=><article key={i.id}><strong>{i.title}</strong><p>{i.message}</p><div>{i.issueUrl&&<a href={i.issueUrl} target="_blank" rel="noreferrer">이슈 ↗</a>} {i.prUrl&&<a href={i.prUrl} target="_blank" rel="noreferrer">PR ↗</a>}</div>{i.error&&<p role="alert">{i.error}</p>}{i.prState==="CLOSED"&&<p>머지되지 않고 닫힌 PR입니다.</p>}{!i.prUrl&&i.canPublish&&<button className="button" disabled={busy} onClick={()=>action("/pr",{workId:i.id})}>승인 결과로 PR 만들기</button>}{i.prState==="OPEN"&&<button className="button" disabled={busy} onClick={()=>{if(window.confirm("이 PR을 기본 브랜치에 머지할까요? GitHub 검사가 통과해야 합니다."))void action("/merge",{workId:i.id,head:i.head});}}>PR 머지</button>}</article>)}{!board?.items.some(i=>i.column===key)&&<p>아직 없습니다</p>}</section>)}</div>
 </>:<details><summary>GitHub 저장소와 Projects 연결</summary><form onSubmit={e=>{e.preventDefault();void action("",{repo,owner,number:Number(number)});}}><label>저장소<input required placeholder="owner/repository" value={repo} onChange={e=>setRepo(e.target.value)}/></label><label>Projects 소유자<input required value={owner} onChange={e=>setOwner(e.target.value)}/></label><label>Projects 번호<input type="number" min="1" required value={number} onChange={e=>setNumber(e.target.value)}/></label><p>gh auth refresh -s project로 권한을 승인하세요. 보드의 Status 옵션은 Todo, In Progress, In Review, Done을 사용합니다. 연결 후 새 작업 제목이 GitHub 이슈에 공개됩니다. 대화와 공유 문서는 올리지 않습니다.</p><button className="button" disabled={busy}>보드 연결</button></form></details>}</section>;
}
