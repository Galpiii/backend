package com.github.galpiii.galpi.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cloud.aws")
public record AwsS3Properties(@NotBlank String region, @NotNull @Valid S3 s3) {

    public record S3(@NotBlank String bucket) {
    }
}
