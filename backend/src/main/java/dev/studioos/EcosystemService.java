package dev.studioos;

import static dev.studioos.Domain.*;
import static dev.studioos.PackDomain.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EcosystemService {
  private final Store store;
  private final RuntimeService runtime;
  private final WorkflowService workflows;
  private final CapabilityService skills;
  private final TransactionTemplate tx;
  private final CatalogFetcher fetcher;
  private final ObjectMapper json;
  public EcosystemService(Store store, RuntimeService runtime, WorkflowService workflows,
      CapabilityService skills, TransactionTemplate tx, CatalogFetcher fetcher, ObjectMapper mapper) {
    this.store=store; this.runtime=runtime; this.workflows=workflows; this.skills=skills; this.tx=tx; this.fetcher=fetcher;
    json=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    json.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }
  public List<Pack> packs() { return store.all("pack",Pack.class); }
  public List<Installation> installations() { return store.all("pack-install",Installation.class); }
  public List<Catalog> catalogs() { return store.all("catalog",Catalog.class); }
  public Pack pack(String id) { return store.get("pack",id,Pack.class); }
  private static void text(String s,int max,String label) {
    if(s==null || s.isBlank() || s.length()>max) throw new IllegalArgumentException(label+" 값이 필요합니다 (최대 "+max+")");
  }
  static String hash(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
  private Manifest parse(String content) throws Exception {
    if(content==null || content.getBytes(StandardCharsets.UTF_8).length>2097152) throw new IllegalArgumentException("Pack은 2 MiB 이하여야 합니다");
    try { return json.readValue(content,Manifest.class); }
    catch(Exception e) { throw new IllegalArgumentException("Pack JSON 규격 오류: "+e.getMessage(),e); }
  }
  private String encode(Manifest m) {
    try { return json.writeValueAsString(m); } catch(Exception e) { throw new IllegalStateException(e); }
  }
  private List<WorkflowDomain.Node> bind(Template template,String agentId) {
    var copy=store.decode(store.encode(template),Template.class);
    copy.nodes.forEach(n->n.agentId=agentId);
    return copy.nodes;
  }
  public Pack preview(String content) throws Exception {
    Manifest m=parse(content);
    if(m==null || !"studio.pack/v1".equals(m.schema)) throw new IllegalArgumentException("지원 규격: studio.pack/v1");
    text(m.name,80,"Pack 이름");
    if(!m.name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IllegalArgumentException("Pack 이름은 kebab-case입니다");
    if(m.version==null || !m.version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) throw new IllegalArgumentException("버전은 major.minor.patch 형식입니다");
    text(m.description,2000,"설명"); text(m.author,120,"작성자"); text(m.license,80,"라이선스");
    if(m.skills==null || m.workflows==null || m.skills.size()>20 || m.workflows.size()>10 || m.skills.size()+m.workflows.size()==0)
      throw new IllegalArgumentException("Pack은 Skill 최대 20개, Workflow 최대 10개가 필요합니다");
    Set<String> names=new HashSet<>();
    for(var entry:m.skills) {
      if(entry==null || entry.name==null || !names.add(entry.name)) throw new IllegalArgumentException("Skill 이름 중복 또는 누락");
      var s=skills.previewFiles(entry.files);
      if(!entry.name.equals(s.name)) throw new IllegalArgumentException("Skill 이름과 SKILL.md 이름이 다릅니다");
      if(s.checks.stream().anyMatch(c->!c.available())) throw new IllegalArgumentException("Skill 검증 실패: "+s.name+" "+s.checks);
    }
    names.clear();
    for(var t:m.workflows) {
      if(t==null) throw new IllegalArgumentException("Workflow 템플릿이 필요합니다");
      text(t.name,120,"템플릿 이름");
      if(!names.add(t.name) || t.nodes==null) throw new IllegalArgumentException("중복 템플릿 또는 단계 누락");
      for(var n:t.nodes) if(n==null || !"$agent".equals(n.agentId) || n.testCommand==null)
        throw new IllegalArgumentException("템플릿은 agentId=$agent 및 명시적 testCommand가 필요합니다");
      workflows.validate(bind(t,"demo-developer"),new Project("preview","preview","", "",List.of(),now()));
    }
    Pack p=new Pack();p.manifest=m;p.digest=hash(encode(m).getBytes(StandardCharsets.UTF_8));
    p.checks=List.of(new Requirement("Pack 규격",true,"경로·용량·Skill·Workflow DAG·명령 형식 확인"),new Requirement("실행 권한",true,"설치는 실행하지 않으며 Skill 활성화 및 작업 승인은 별도입니다"));
    return p;
  }
  public synchronized Pack importPack(String content,String source) throws Exception {
    Pack p=preview(content);
    for(var old:packs()) if(old.manifest.name.equals(p.manifest.name) && old.manifest.version.equals(p.manifest.version)) {
      if(old.digest.equals(p.digest)) return old;
      throw new IllegalArgumentException("같은 이름·버전에 다른 내용이 등록되어 있습니다. 버전을 올리세요");
    }
    p.id=id();p.createdAt=now();p.source=source;
    store.save("pack",p.id,p,"pack.imported",null,Map.of("name",p.manifest.name,"digest",p.digest));return p;
  }
  public String export(String id) { return encode(pack(id).manifest); }
  public List<Map<String,String>> templates() {
    return List.of(Map.of("id","readme-starter","name","README Starter","description","Codex README 작업 + 검증 안내 Skill"),Map.of("id","offline-demo","name","Offline Demo","description","AI 인증 없이 Runtime·검증·승인을 체험합니다"));
  }
  public String template(String name) throws Exception {
    if(templates().stream().noneMatch(t->t.get("id").equals(name))) throw new IllegalArgumentException("알 수 없는 공개 템플릿");
    try(var in=getClass().getResourceAsStream("/packs/"+name+".json")) { return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8); }
  }
  public Pack importTemplate(String name) throws Exception { return importPack(template(name),"builtin:"+name); }
  public Installation install(String packId,String digest,String projectId,String agentId) throws Exception {
    // Match Workflow -> Runtime -> Capability lock order, with the whole install transaction inside.
    synchronized(workflows) { synchronized(runtime) { synchronized(skills) {
      Pack p=pack(packId);
      if(!Objects.equals(p.digest,digest)) throw new IllegalArgumentException("검토한 Pack digest와 다릅니다");
      preview(export(packId));
      var project=runtime.project(projectId); store.get("agent",agentId,Agent.class);
      for(var old:installations()) if(old.packId.equals(packId)&&old.projectId.equals(projectId)&&old.agentId.equals(agentId)) return old;
      for(var t:p.manifest.workflows) workflows.validate(bind(t,agentId),project);
      return tx.execute(status->{
        Installation i=new Installation();i.id=id();i.packId=packId;i.digest=digest;i.projectId=projectId;i.agentId=agentId;i.createdAt=now();
        for(var s:p.manifest.skills) i.skillIds.add(skills.registerFiles(projectId,"pack:"+packId+"/"+s.name,s.files).id);
        for(var t:p.manifest.workflows) i.workflowIds.add(workflows.saveDefinition(null,t.name,projectId,bind(t,agentId),null).id);
        store.save("pack-install",i.id,i,"pack.installed",null,Map.of("packId",packId,"projectId",projectId));return i;
      });
    } } }
  }
  public Catalog addCatalog(String name,String url) throws Exception {
    text(name,120,"카탈로그 이름");
    Catalog c=new Catalog();c.id=id();c.name=name;c.url=url;
    return fetchCatalog(c);
  }
  public Catalog refreshCatalog(String id) throws Exception { return fetchCatalog(store.get("catalog",id,Catalog.class)); }
  private Catalog fetchCatalog(Catalog c) throws Exception {
    Index index=json.readValue(fetcher.fetch(c.url),Index.class);
    if(index==null || !"studio.catalog/v1".equals(index.schema) || index.entries==null || index.entries.size()>200)
      throw new IllegalArgumentException("카탈로그는 studio.catalog/v1 및 최대 200개 항목입니다");
    Set<String> keys=new HashSet<>();
    for(var e:index.entries) {
      if(e==null) throw new IllegalArgumentException("빈 카탈로그 항목");
      text(e.name,80,"이름");text(e.version,30,"버전");text(e.description,2000,"설명");
      if(e.sha256==null || !e.sha256.matches("[a-f0-9]{64}") || !keys.add(e.name+"@"+e.version)) throw new IllegalArgumentException("SHA-256 또는 중복 항목 오류");
      // Validate syntax here; resolve and check target IP immediately before fetching a selected pack.
      var uri=java.net.URI.create(e.url);
      if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) throw new IllegalArgumentException("Pack URL은 HTTPS여야 합니다");
    }
    c.entries=index.entries;c.updatedAt=now();
    synchronized(this) {
      for(var old:catalogs()) if(old.url.equals(c.url)) c.id=old.id;
      store.save("catalog",c.id,c,"catalog.refreshed",null,Map.of("name",c.name,"count",c.entries.size()));
    }
    return c;
  }
  public Pack fetchPack(String catalogId,String sha256) throws Exception {
    var c=store.get("catalog",catalogId,Catalog.class);
    var e=c.entries.stream().filter(x->x.sha256.equals(sha256)).findFirst().orElseThrow(()->new IllegalArgumentException("카탈로그 항목을 찾을 수 없습니다"));
    byte[] content=fetcher.fetch(e.url);
    if(!hash(content).equals(e.sha256)) throw new IllegalArgumentException("다운로드 SHA-256 불일치");
    Pack preview=preview(new String(content,StandardCharsets.UTF_8));
    if(!e.name.equals(preview.manifest.name)||!e.version.equals(preview.manifest.version)) throw new IllegalArgumentException("카탈로그와 패키지 식별자가 다릅니다");
    return importPack(new String(content,StandardCharsets.UTF_8),e.url);
  }
}
