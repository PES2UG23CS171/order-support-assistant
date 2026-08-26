package com.acme.ordersupport.config;

import com.acme.ordersupport.order.repo.CustomerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CustomerRepository customers, ObjectMapper objectMapper)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthenticationFilter(customers),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> {
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.UNAUTHORIZED, "A valid X-API-Key header is required.");
                            problem.setTitle("Unauthorized");
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), problem);
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.FORBIDDEN, "This customer may not perform that operation.");
                            problem.setTitle("Forbidden");
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), problem);
                        }))
                .build();
    }
}
