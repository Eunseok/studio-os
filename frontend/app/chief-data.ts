"use client";
import { useCallback, useEffect, useState } from "react";
export type Conversation={id:string;projectId:string;state:string;runId?:string;error?:string;feedbackPending?:boolean;previousRunIds?:string[];messages:{role:string;text:string;at:string}[];plan?:{response:string;questions:string[];steps:{title:string;instruction:string;requiredSkills?:string[];skillReason?:string}[]}};
export const conversationStates:Record<string,string>={THINKING:"총괄이 프로젝트를 살펴보고 있습니다",PLAN_READY:"진행할 내용을 확인해 주세요",RUNNING:"총괄이 담당자들과 진행하고 있습니다",WAITING_RESULT:"결과를 확인해 주세요",COMPLETE:"완료",NEEDS_HELP:"확인이 필요합니다",STOPPED:"중지됨",DISCUSSING:"함께 방향 정하기"};

export function useConversations() {
 const [conversations, setConversations] = useState<Conversation[]>([]);
 const [error, setError] = useState("");
 const [loading, setLoading] = useState(true);
 const load = useCallback(async () => {
  const response = await fetch("/api/chief", {cache:"no-store"});
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || "대화를 불러오지 못했습니다");
  setConversations(data);
  setError("");
 }, []);
 useEffect(() => {
  let closed = false, pending = false;
  const poll = async () => {
   if (pending) return;
   pending = true;
   try { await load(); } catch (e) { if (!closed) setError((e as Error).message); }
   finally { pending = false; if (!closed) setLoading(false); }
  };
  void poll();
  const timer = setInterval(() => void poll(), 2000);
  return () => {closed = true; clearInterval(timer);};
 }, [load]);
 return {conversations, error, loading, load};
}
