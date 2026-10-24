package dev.studioos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StudioApplication {
  public static void main(String[] args) {
    SpringApplication.run(StudioApplication.class, args);
  }
}
