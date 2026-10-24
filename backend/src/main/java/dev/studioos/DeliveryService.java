package dev.studioos;
import static dev.studioos.Domain.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Only explicit result approval queues publication. Restart/installation are outside this boundary. */
@Service
public class DeliveryService {
 public static class Delivery {
  public String id,conversationId,projectId,taskId,status="NONE",message="",prUrl,commit,updatedAt;
 }
 private final ChiefService chief;private final RuntimeService runtime;private final GithubService github;private final Store store;
 private final ExecutorService executor=Executors.newSingleThreadExecutor();private final AtomicBoolean busy=new AtomicBoolean();
 public DeliveryService(ChiefService chief,RuntimeService runtime,GithubService github,Store store){this.chief=chief;this.runtime=runtime;this.github=github;this.store=store;}
 @PreDestroy public void close(){executor.shutdownNow();}
 public Delivery get(String id){return store.all("delivery",Delivery.class).stream().filter(d->id.equals(d.conversationId)).findFirst().orElseGet(Delivery::new);}
 private void save(Delivery d){d.updatedAt=now();store.save("delivery",d.id,d,"delivery.updated",d.taskId,Map.of("status",d.status,"message",d.message));}
 public synchronized Delivery approveAndDeliver(String id){
  Delivery existing=get(id);if(!"NONE".equals(existing.status))return existing;
  var c=chief.get(id);if(!"COMPLETE".equals(c.state))c=chief.approve(id);
  var r=store.get("workflow-run",c.runId,WorkflowDomain.Run.class);
  if(!"SUCCEEDED".equals(r.status))throw new IllegalArgumentException("전체 결과 승인이 먼저 필요합니다");
  Task finalTask=null;
  for(var step:r.steps.values())if(!step.attempts.isEmpty()){
   Task t=runtime.task(step.attempts.getLast());Set<String> included=new HashSet<>();included.add(t.workflowNodeId);t.workflowInputs.forEach(i->included.add(i.nodeId()));if(included.containsAll(r.steps.keySet()))finalTask=t;
  }
  if(finalTask==null)throw new IllegalArgumentException("통합된 최종 결과가 없습니다");
  Delivery d=new Delivery();d.id="delivery:"+id;d.conversationId=id;d.projectId=c.projectId;d.taskId=finalTask.id;d.status="QUEUED";d.message="결과 승인 완료 · 검증한 코드를 반영합니다";save(d);return d;
 }
 public synchronized Delivery retry(String id){Delivery d=get(id);if(!Set.of("BLOCKED","LOCAL_PENDING").contains(d.status))return d;d.status=d.prUrl!=null&&"MERGED".equals(github.works(d.projectId).stream().filter(w->d.prUrl.equals(w.prUrl)).map(w->w.prState).findFirst().orElse(""))?"LOCAL_PENDING":"QUEUED";save(d);return d;}
 @Scheduled(fixedDelay=5000) public void tick(){if(!busy.compareAndSet(false,true))return;executor.submit(()->{try{for(Delivery d:store.all("delivery",Delivery.class))if(Set.of("QUEUED","PUBLISHING","MERGED","LOCAL_PENDING").contains(d.status))deliver(d);}finally{busy.set(false);}});}
 private void deliver(Delivery d){try{
  boolean linked=github.bindings().stream().anyMatch(b->b.projectId.equals(d.projectId));
  if(!linked){Task t=runtime.applyToProject(d.taskId);d.commit=t.appliedCommit;d.status="APPLIED";d.message="승인한 코드를 프로젝트에 반영했습니다. 실행 중인 앱은 평소 실행 방법으로 다시 시작하세요.";save(d);return;}
  if(!Set.of("MERGED","LOCAL_PENDING").contains(d.status)){
   d.status="PUBLISHING";d.message="승인한 결과로 PR을 준비하고 GitHub 검사를 확인합니다";save(d);
   github.sync(d.projectId);Task t=runtime.task(d.taskId);String key="github-work:"+(t.workflowRunId==null?t.id:t.workflowRunId);
   var work=github.publish(d.projectId,key);d.prUrl=work.prUrl;save(d);
   if(!"MERGED".equals(work.prState))work=github.merge(d.projectId,key,work.head);
   if(!"MERGED".equals(work.prState))throw new IllegalArgumentException("PR 머지를 확인하지 못했습니다");
   d.status="MERGED";d.message="GitHub 머지 완료 · 로컬 프로젝트 업데이트 중";save(d);
  }
  try{var result=github.pull(d.projectId);d.commit=result.get("commit");d.status="APPLIED";d.message="코드 반영 완료. 실행 중인 앱은 평소 실행 방법으로 다시 시작하세요.";}
  catch(Exception e){d.status="LOCAL_PENDING";d.message="GitHub 머지는 완료됐습니다. 로컬 업데이트 대기: "+e.getMessage();}
  save(d);
 }catch(Exception e){d.status="BLOCKED";d.message="승인한 결과를 보존했습니다. 반영을 마치지 못했습니다: "+e.getMessage();save(d);}}
}
