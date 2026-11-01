package com.example.logproducer;

import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LogProducerApplication {

    public static void main(String[] args) {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            hostname = UUID.randomUUID().toString();
        }
        System.setProperty("LOG_FILE_NAME", hostname);

        SpringApplication.run(LogProducerApplication.class, args);
    }
}
