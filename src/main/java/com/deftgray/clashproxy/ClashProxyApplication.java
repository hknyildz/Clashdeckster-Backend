package com.deftgray.clashproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ClashProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClashProxyApplication.class, args);
    }

}
