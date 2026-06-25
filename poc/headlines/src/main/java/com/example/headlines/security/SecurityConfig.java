/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security for the multi-user app: OIDC login against Keycloak.
 *
 * <p>Vaadin 25 configures security through {@link VaadinSecurityConfigurer} (the older
 * {@code VaadinWebSecurity} base class is gone). {@code oauth2LoginPage} points at Spring's own
 * authorization endpoint {@code /oauth2/authorization/{registrationId}} — here {@code keycloak},
 * matching the registration id in {@code application.properties} — so an unauthenticated request to
 * any protected route redirects to Keycloak. After logout the user lands back on the app root.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.with(VaadinSecurityConfigurer.vaadin(), configurer ->
                configurer.oauth2LoginPage("/oauth2/authorization/keycloak", "{baseUrl}"));
        return http.build();
    }
}
