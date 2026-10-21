package org.example.deal_with_devil_csa.config;

import java.util.List;

import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.security.AdminAuditFilter;
import org.example.deal_with_devil_csa.security.JwtAuthenticationFilter;
import org.example.deal_with_devil_csa.security.JwtService;
import org.example.deal_with_devil_csa.security.SecurityErrorWriter;
import org.example.deal_with_devil_csa.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity   // switches on the @PreAuthorize checks on the service functions
public class SecurityConfig
{
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, UserRepository userRepository,
                                           SecurityErrorWriter errorWriter, AuditService auditService) throws Exception
    {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userRepository, errorWriter);
        AdminAuditFilter auditFilter = new AdminAuditFilter(auditService);

        http
                .csrf(AbstractHttpConfigurer::disable)          // stateless Bearer-token API, no cookies
                .cors(Customizer.withDefaults())                // uses the CorsConfigurationSource bean below
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/google").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/admin-fallback").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                errorWriter.write(request, response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.write(request, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Right after the JWT filter, so the signed-in user is known when admin calls are audited.
                .addFilterAfter(auditFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Nothing in this app signs in with a username and password (login is Google or the fallback admin endpoint, then
     * our own JWT). Defining this bean stops Spring Boot from creating its default user and printing a random
     * password at startup. It never finds anybody.
     */
    @Bean
    public UserDetailsService userDetailsService()
    {
        return username ->
        {
            throw new UsernameNotFoundException("Password sign-in is not used.");
        };
    }

    /** Lets the React app (on another origin) send the Authorization header. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins)
    {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
