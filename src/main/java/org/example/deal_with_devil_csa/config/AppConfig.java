package org.example.deal_with_devil_csa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({GameProperties.class, AuthProperties.class})
public class AppConfig
{
}
