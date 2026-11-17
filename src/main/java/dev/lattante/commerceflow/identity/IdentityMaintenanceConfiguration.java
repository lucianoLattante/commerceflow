package dev.lattante.commerceflow.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenCleanupSettings.class)
class IdentityMaintenanceConfiguration {}
