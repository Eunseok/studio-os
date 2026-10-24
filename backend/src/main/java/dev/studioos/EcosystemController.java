package dev.studioos;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ecosystem")
public class EcosystemController {
  private final EcosystemService ecosystem;
  private final OnboardingService onboarding;
  public EcosystemController(EcosystemService ecosystem,OnboardingService onboarding) { this.ecosystem=ecosystem;this.onboarding=onboarding; }
  public record Content(String content) {}
  public record Install(String digest,String projectId,String agentId) {}
  public record CatalogRequest(String name,String url) {}
  public record Fetch(String sha256) {}
  public record Demo(String requestKey) {}
  @GetMapping public Map<String,Object> state() { return Map.of("packs",ecosystem.packs(),"installations",ecosystem.installations(),"catalogs",ecosystem.catalogs(),"templates",ecosystem.templates()); }
  @PostMapping("/preview") public PackDomain.Pack preview(@RequestBody Content r) throws Exception { return ecosystem.preview(r.content()); }
  @PostMapping("/packs") public PackDomain.Pack register(@RequestBody Content r) throws Exception { return ecosystem.importPack(r.content(),"upload"); }
  @GetMapping("/packs/{id}/export") public ResponseEntity<String> export(@PathVariable String id) {
    var pack=ecosystem.pack(id);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+pack.manifest.name+"-"+pack.manifest.version+".json\"").body(ecosystem.export(id));
  }
  @PostMapping("/packs/{id}/install") public PackDomain.Installation install(@PathVariable String id,@RequestBody Install r) throws Exception { return ecosystem.install(id,r.digest(),r.projectId(),r.agentId()); }
  @GetMapping("/templates/{id}") public String template(@PathVariable String id) throws Exception { return ecosystem.template(id); }
  @PostMapping("/templates/{id}") public PackDomain.Pack importTemplate(@PathVariable String id) throws Exception { return ecosystem.importTemplate(id); }
  @PostMapping("/catalogs") public PackDomain.Catalog addCatalog(@RequestBody CatalogRequest r) throws Exception { return ecosystem.addCatalog(r.name(),r.url()); }
  @PostMapping("/catalogs/{id}/refresh") public PackDomain.Catalog refresh(@PathVariable String id) throws Exception { return ecosystem.refreshCatalog(id); }
  @PostMapping("/catalogs/{id}/fetch") public PackDomain.Pack fetch(@PathVariable String id,@RequestBody Fetch r) throws Exception { return ecosystem.fetchPack(id,r.sha256()); }
  @PostMapping("/demo") public Map<String,String> demo(@RequestBody Demo r) throws Exception { return onboarding.demo(r.requestKey()); }
}
