package com.acme.ordersupport;

import com.acme.ordersupport.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// Authentication is entirely API-key based (see ApiKeyAuthenticationFilter), so the
// default in-memory user Spring Security would otherwise create is both unused and
// a generated credential sitting in the startup log. Excluded rather than tolerated.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class OrderSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderSupportApplication.class, args);
    }
}
