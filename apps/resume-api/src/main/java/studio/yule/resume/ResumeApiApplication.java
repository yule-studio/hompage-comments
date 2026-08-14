package studio.yule.resume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResumeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeApiApplication.class, args);
    }
}
