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

import com.vaadin.flow.shared.Registration;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out signal that "the shared feed refresh just added new articles", so open browser sessions can
 * react live (a toast) instead of only learning about new news on their next visit — the in-session half
 * of RSSOwl's new-item notifications. {@link FeedFetchService} fires {@link #broadcast()} after a refresh
 * that saved anything; each open {@code HeadlinesView} registers a listener and marshals the update onto
 * its own UI thread via {@code UI.access} (the app runs with {@code @Push}).
 *
 * <p>Listeners are invoked on the caller's (background refresh) thread, so each must do its own
 * {@code UI.access} and keep the callback cheap.
 */
@Component
public class FeedBroadcaster {

    private final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();

    /** Register a listener; the returned {@link Registration} removes it (call on view detach). */
    public Registration register(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Notify all registered listeners that new articles arrived. */
    public void broadcast() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException ignored) {
                // A dead/detached UI must not break the fan-out to the others.
            }
        }
    }
}
