package zxf.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FileUploadApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileUploadApplication.class, args);
    }
}
