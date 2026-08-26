package com.acme.ordersupport.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    /**
     * Time is injected, never read from a static. Return windows and confirmation
     * expiry are both "how long ago was that", and a test that has to sleep for ten
     * minutes to prove a token expired is a test nobody runs.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
