package com.medbox.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 家用智能药品箱 · Java 后端启动类。
 *
 * <p>对应 07 清单地基第 1 项 {@code feat/backend-scaffold}。
 */
@SpringBootApplication
public class MedboxServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedboxServerApplication.class, args);
    }
}
