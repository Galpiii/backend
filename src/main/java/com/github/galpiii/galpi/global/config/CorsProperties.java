package com.github.galpiii.galpi.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "galpi.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
