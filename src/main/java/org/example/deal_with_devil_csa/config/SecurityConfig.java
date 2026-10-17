package org.example.deal_with_devil_csa.config;

import java.util.List;

import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.security.JwtAuthenticationFilter;
import org.example.deal_with_devil_csa.security.JwtService;
import org.example.deal_with_devil_csa.security.SecurityErrorWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig
{
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, UserRepository userRepository,
                                           SecurityErrorWriter errorWriter) throws Exception
    {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userRepository, errorWriter);

        http
                .csrf(AbstractHttpConfigurer::disable)          // stateless Bearer-token API, no cookies
                .cors(Customizer.withDefaults())                // uses the CorsConfigurationSource bean below
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/google").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                errorWriter.write(request, response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.write(request, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
