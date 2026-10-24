"use client";
import {useEffect,useState} from "react";
type Job={state:string;message?:string;reason?:string;sources?:string[];scopes?:string[];attempt?:number};
export default function HiringProgress({conversationId}:{conversationId:string}){
 const [job,setJob]=useState<Job>();const [error,setError]=useState("");
 useEffect(()=>{let alive=true;const load=async()=>{try{const r=await fetch(`/api/chief/${conversationId}/hiring`,{cache:"no-store"});if(!r.ok)throw new Error("전문가 준비 상태를 다시 연결하고 있습니다");const value=await r.json();if(alive){setJob(value);setError("");}}catch(e){if(alive)setError((e as Error).message);}};void load();const timer=setInterval(load,2000);return()=>{alive=false;clearInterval(timer);};},[conversationId]);
 if(!job||job.state==="NONE")return error?<p role="status">{error}</p>:null;
 return <section className="hiring-progress" aria-label="총괄의 전문가 확보 진행"><h3>{job.state==="READY"?"담당자를 확보했습니다":job.state==="FAILED"?"전문가 확보에 확인이 필요합니다":job.state==="CANCELLED"?"전문가 준비를 중지했습니다":"역량 담당이 필요한 전문가를 준비하고 있어요"}</h3><p role="status">{job.message}</p>{job.scopes?.length?<p>이번 작업에 필요한 전문성: {job.scopes.join(" · ")}</p>:null}<p>총괄 → 역량 담당: 후보 탐색·평가 준비 → 전문 검토: 평가 확인 → 총괄: 담당자 배정·작업 재개</p>{job.reason&&<p>{job.reason}</p>}{!!job.sources?.length&&<details><summary>선정 이유와 출처 보기</summary><ul>{job.sources.map((s,i)=><li key={i}>{s}</li>)}</ul></details>}{error&&<p role="status">{error}</p>}</section>;
}
