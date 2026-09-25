package com.intwfs.mintwf.tmf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application exposing the TM Forum Open APIs used by mintwf.
 */
@SpringBootApplication
public class MintwfTmfApplication {

    public static void main(String[] args) {
        SpringApplication.run(MintwfTmfApplication.class, args);
    }
}
