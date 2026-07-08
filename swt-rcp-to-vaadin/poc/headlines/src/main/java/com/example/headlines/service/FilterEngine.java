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

import com.example.headlines.NewsItem;
import com.example.headlines.data.NewsFilter;
import com.example.headlines.data.NewsFilter.Condition;

/**
 * Pure matching logic for a {@link NewsFilter} against a {@link NewsItem} — the heart of the rules
 * engine, kept free of JPA/Spring so it is directly unit-testable. Each condition is a case-insensitive
 * "contains" over the chosen field; conditions combine with the filter's ALL/AND or ANY/OR mode.
 */
public final class FilterEngine {

    private FilterEngine() {}

    public static boolean matches(NewsFilter filter, NewsItem item) {
        var conditions = filter.getConditions();
        if (conditions.isEmpty()) return false; // a filter with no conditions matches nothing (never mass-apply)
        return switch (filter.getMatchMode()) {
            case ALL -> conditions.stream().allMatch(c -> matches(c, item));
            case ANY -> conditions.stream().anyMatch(c -> matches(c, item));
        };
    }

    private static boolean matches(Condition c, NewsItem item) {
        String needle = c.getValue();
        if (needle == null || needle.isBlank()) return false;
        String hay = switch (c.getField()) {
            case TITLE -> item.title();
            case AUTHOR -> item.author();
            case FEED -> item.feed();
            case CONTENT -> item.content();
        };
        return hay != null && hay.toLowerCase().contains(needle.toLowerCase());
    }
}
