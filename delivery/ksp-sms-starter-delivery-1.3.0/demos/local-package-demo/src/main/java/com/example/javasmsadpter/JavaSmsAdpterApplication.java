package com.example.javasmsadpter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JavaSmsAdpterApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaSmsAdpterApplication.class, args);
    }
}
