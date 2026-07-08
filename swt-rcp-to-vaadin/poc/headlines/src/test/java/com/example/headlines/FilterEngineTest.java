/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines;

import com.example.headlines.NewsItem.State;
import com.example.headlines.data.NewsFilter;
import com.example.headlines.data.NewsFilter.Condition;
import com.example.headlines.data.NewsFilter.Field;
import com.example.headlines.data.NewsFilter.MatchMode;
import com.example.headlines.service.FilterEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Matching logic for the rules engine — fields, case-insensitivity, ALL/ANY, and the empty-filter guard. */
class FilterEngineTest {

    private static NewsItem item(String title, String author, String feed, String content) {
        NewsItem n = new NewsItem(1, title, author, "Cat", feed, LocalDateTime.now(),
                State.UNREAD, false, null, "https://x/1", false);
        n.setContent(content);
        return n;
    }

    private static NewsFilter filter(MatchMode mode, Condition... conditions) {
        NewsFilter f = new NewsFilter("alice", "f");
        f.setMatchMode(mode);
        f.setConditions(List.of(conditions));
        return f;
    }

    @Test
    void singleConditionContainsIsCaseInsensitive() {
        NewsItem n = item("Linux Kernel 6.14 released", "Torvalds", "LWN", "scheduler changes");
        assertTrue(FilterEngine.matches(filter(MatchMode.ALL, new Condition(Field.TITLE, "kernel")), n));
        assertTrue(FilterEngine.matches(filter(MatchMode.ALL, new Condition(Field.TITLE, "LINUX")), n));
        assertFalse(FilterEngine.matches(filter(MatchMode.ALL, new Condition(Field.TITLE, "windows")), n));
    }

    @Test
    void matchesAcrossFields() {
        NewsItem n = item("Release notes", "Greg KH", "Kernel Planet", "<p>mainline merge window</p>");
        assertTrue(FilterEngine.matches(filter(MatchMode.ALL, new Condition(Field.AUTHOR, "greg")), n));
        assertTrue(FilterEngine.matches(filter(MatchMode.ALL, new Condition(Field.FEED, "planet")), n));
        assertTrue(FilterEngine.matches(filter(MatchMode.ALL, new Condition(Field.CONTENT, "merge window")), n));
    }

    @Test
    void allRequiresEveryCondition_anyRequiresOne() {
        NewsItem n = item("Linux news", "Anon", "LWN", "");
        Condition linux = new Condition(Field.TITLE, "linux");
        Condition windows = new Condition(Field.TITLE, "windows");
        assertFalse(FilterEngine.matches(filter(MatchMode.ALL, linux, windows), n), "ALL: not both");
        assertTrue(FilterEngine.matches(filter(MatchMode.ANY, linux, windows), n), "ANY: one is enough");
    }

    @Test
    void filterWithNoConditionsMatchesNothing() {
        assertFalse(FilterEngine.matches(filter(MatchMode.ALL), item("anything", "x", "y", "z")),
                "an empty filter must never mass-apply");
    }
}
