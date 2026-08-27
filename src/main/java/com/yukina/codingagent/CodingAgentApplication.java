package com.yukina.codingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodingAgentApplication.class, args);
    }

}
