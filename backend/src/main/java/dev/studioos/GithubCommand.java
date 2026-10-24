package dev.studioos;

import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class GithubCommand {
  public String run(Path cwd, String... args) throws Exception {
    return Processes.quick(cwd, args);
  }
}
