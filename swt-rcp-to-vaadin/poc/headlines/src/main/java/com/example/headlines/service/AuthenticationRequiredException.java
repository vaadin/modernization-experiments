/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines.service;

/**
 * Thrown when a feed responds with HTTP 401 — it requires authentication and either no credentials
 * were supplied or they were rejected. Mirrors RSSOwl's {@code AuthenticationRequiredException}; the
 * UI reacts by prompting for username/password ("Feed requires authentication"). Carries the optional
 * realm parsed from the {@code WWW-Authenticate} header.
 */
public class AuthenticationRequiredException extends RuntimeException {

    private final String realm;

    public AuthenticationRequiredException(String url, String realm) {
        super("Authentication required for " + url + (realm != null ? " (realm: " + realm + ")" : ""));
        this.realm = realm;
    }

    public String getRealm() {
        return realm;
    }
}
