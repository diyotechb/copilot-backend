package com.example.livetranscription.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter();
    }

    @Bean
    @ConditionalOnExpression("'${aws.cognito.user-pool-id:}' != '' and '${aws.cognito.region:}' != ''")
    public CognitoTokenFilter cognitoTokenFilter(
            @Value("${aws.cognito.region}") String region,
            @Value("${aws.cognito.user-pool-id}") String userPoolId) throws java.net.MalformedURLException {
        String issuer = String.format("https://cognito-idp.%s.amazonaws.com/%s", region, userPoolId);
        return new CognitoTokenFilter(issuer);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    ObjectProvider<CognitoTokenFilter> cognitoFilterProvider,
                                                    RateLimitFilter rateLimitFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        CognitoTokenFilter cognitoFilter = cognitoFilterProvider.getIfAvailable();
        if (cognitoFilter != null) {
            http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().permitAll())
                    .addFilterBefore(cognitoFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            http
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
