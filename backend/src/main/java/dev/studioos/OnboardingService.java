package dev.studioos;

import static dev.studioos.Domain.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OnboardingService {
  private final RuntimeService runtime;
  private final EcosystemService ecosystem;
  private final WorkflowService workflows;
  private final Path demoRoot;
  public OnboardingService(RuntimeService runtime,EcosystemService ecosystem,WorkflowService workflows,
      @Value("${studio.demo-root:./examples/first-run}") String demoRoot) {
    this.runtime=runtime;this.ecosystem=ecosystem;this.workflows=workflows;this.demoRoot=Path.of(demoRoot).toAbsolutePath().normalize();
  }
  public synchronized Map<String,String> demo(String requestKey) throws Exception {
    if(requestKey==null || !requestKey.matches("[a-zA-Z0-9_-]{8,64}")) throw new IllegalArgumentException("8~64자 요청 키가 필요합니다");
    // This generated fixture has no package-controlled scripts or paths.
    Files.createDirectories(demoRoot);
    Path repo=demoRoot.resolve(requestKey);
    String readme="# Hello Studio OS\n\nThis is a disposable first-run demo repository.\n";
    if(!Files.exists(repo)) {
      Files.createDirectory(repo);
      Files.writeString(repo.resolve("README.md"),readme,StandardOpenOption.CREATE_NEW);
    }
    if(Files.isSymbolicLink(repo) || !Files.isRegularFile(repo.resolve("README.md"),LinkOption.NOFOLLOW_LINKS)
        || !Files.readString(repo.resolve("README.md")).equals(readme)) throw new IllegalArgumentException("데모 경로에 다른 파일이 있습니다. 새 요청 키를 사용하세요");
    if(!Files.exists(repo.resolve(".git"))) {
      Processes.quick(repo,"git","init");Processes.quick(repo,"git","add","README.md");
      Processes.quick(repo,"git","-c","user.name=Studio Demo","-c","user.email=demo@example.invalid","-c","core.hooksPath=/dev/null","commit","-m","First-run demo");
    }
    String real=repo.toRealPath().toString();
    Project p=runtime.projects().stream().filter(x->x.path().equals(real)).findFirst().orElse(null);
    if(p==null) p=runtime.addProject("Studio OS Demo",real,"Disposable demo: original repository stays unchanged.",List.of("python3","-c","from pathlib import Path; assert Path('STUDIO_DEMO.md').exists()"));
    var pack=ecosystem.importTemplate("offline-demo");
    var install=ecosystem.install(pack.id,pack.digest,p.id(),"demo-developer");
    var run=workflows.start(install.workflowIds.getFirst(),"demo-"+requestKey);
    return Map.of("projectId",p.id(),"runId",run.id,"taskId",run.steps.get("demo").attempts.getFirst(),"installationId",install.id);
  }
}
