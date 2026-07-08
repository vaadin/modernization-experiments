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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPML export round-trips through the parser: writing subscriptions to OPML and parsing them back
 * yields the same feeds with their folder paths intact (including nesting and XML-escaped names).
 */
class OpmlTest {

    private static UserNewsService.FeedRef ref(String title, String folder, String url, int pos) {
        return new UserNewsService.FeedRef(pos, title, folder, pos, url, null);
    }

    @Test
    void exportThenParsePreservesFeedsAndFolderPaths() throws Exception {
        List<UserNewsService.FeedRef> subs = List.of(
                ref("Ars Technica", "Computers", "https://arstechnica.com/feed", 0),
                ref("LWN", "Computers/Linux", "https://lwn.net/headlines/rss", 1),
                ref("BBC News", "Uncategorized", "https://bbc.co.uk/rss", 2),
                ref("Tom & Jerry", "Entertainment", "https://example.com/t&j", 3)); // forces XML escaping

        String opml = Opml.write(subs);
        List<DefaultFeeds.Source> parsed = DefaultFeeds.parse(
                new ByteArrayInputStream(opml.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> folderByTitle = parsed.stream()
                .collect(Collectors.toMap(DefaultFeeds.Source::title, DefaultFeeds.Source::category));

        assertEquals(4, parsed.size(), "all feeds survive the round-trip");
        assertEquals("Computers", folderByTitle.get("Ars Technica"));
        assertEquals("Computers/Linux", folderByTitle.get("LWN"), "nested folder path preserved");
        assertEquals("Uncategorized", folderByTitle.get("BBC News"), "loose channel stays top-level");
        assertEquals("Entertainment", folderByTitle.get("Tom & Jerry"), "ampersand title round-trips");
        assertTrue(parsed.stream().anyMatch(s -> s.url().equals("https://lwn.net/headlines/rss")));
    }
}
