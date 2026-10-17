package org.example.deal_with_devil_csa.web.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController
{
    @GetMapping
    public Map<String, Object> health()
    {
        return Map.of("status", "UP", "time", Instant.now().toString());
    }
}
