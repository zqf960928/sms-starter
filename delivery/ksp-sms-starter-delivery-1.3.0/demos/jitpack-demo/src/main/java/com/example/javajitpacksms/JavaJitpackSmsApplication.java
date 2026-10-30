package com.example.javajitpacksms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JavaJitpackSmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaJitpackSmsApplication.class, args);
    }
}
