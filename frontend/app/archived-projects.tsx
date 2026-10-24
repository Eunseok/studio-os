"use client";
import {useState} from "react";
import type {Project} from "./types";
export default function ArchivedProjects({refresh}:{refresh:()=>Promise<void>}) {
 const [items,setItems]=useState<Project[]>([]),[error,setError]=useState("");
 async function load(){try{const r=await fetch("/api/projects/all");const v=await r.json();setItems(v.projects.filter((p:Project)=>v.archived.includes(p.id)));}catch(e){setError((e as Error).message);}}
 return <details className="archived-projects" onToggle={e=>{if(e.currentTarget.open)void load();}}><summary>목록에서 제거한 프로젝트 · 복원</summary><p>저장소 파일과 실행 기록은 보존되어 있습니다.</p>{items.map(p=><p key={p.id}>{p.name} <button onClick={async()=>{try{const r=await fetch(`/api/projects/${p.id}/archive`,{method:"POST",headers:{"Content-Type":"application/json","X-Studio-Client":"1"},body:JSON.stringify({archived:false})});if(!r.ok)throw new Error((await r.json()).message);await load();await refresh();}catch(e){setError((e as Error).message);}}}>목록으로 복원</button></p>)}{error&&<p role="alert">{error}</p>}</details>;
}
