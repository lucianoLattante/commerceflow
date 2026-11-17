package dev.lattante.commerceflow.identity;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("commerceflow.identity.cleanup")
@Validated
public record TokenCleanupSettings(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("200") @Min(1) @Max(1000) int batchSize,
    @DefaultValue("60000") @Min(1000) @Max(3600000) long intervalMs) {}
