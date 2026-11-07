package app.scholarai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class ScholarAiApplication {
  public static void main(String[] args) {
    SpringApplication.run(ScholarAiApplication.class, args);
  }
}
