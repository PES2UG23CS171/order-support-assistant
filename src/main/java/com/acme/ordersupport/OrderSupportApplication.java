package com.acme.ordersupport;

import com.acme.ordersupport.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class OrderSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderSupportApplication.class, args);
    }
}
