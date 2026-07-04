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

import com.example.headlines.JpaTestConfig;
import com.example.headlines.data.ArticleRepository;
import com.example.headlines.data.Feed;
import com.example.headlines.data.FeedRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public-fetch tests for {@link FeedFetchService}: RSS parsing, de-duplication by (feed, link), and
 * RSSOwl's 200-news-per-feed retention cap. Mirrors RSSOwl's interpreter + retention/cleanup tests.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaTestConfig.class)
@Transactional
class FeedFetchServiceTest {

    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;

    private FeedFetchService svc;
    private HttpServer server;
    private String url;
    private volatile int itemCount;
    private volatile boolean withCategories;
    private volatile boolean withEnclosure;

    @BeforeEach
    void setUp() throws IOException {
        svc = new FeedFetchService(feeds, articles, new ArticleSearchService(null), new FeedBroadcaster());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", ex -> {
            byte[] body = rss(itemCount).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/feed";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String rss(int items) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title>");
        for (int i = 0; i < items; i++) {
            sb.append("<item><title>Item ").append(i).append("</title>")
              .append("<link>https://example/").append(i).append("</link>");
            if (withCategories) sb.append("<category>Tech</category><category>Business</category>");
            if (withEnclosure) sb.append("<enclosure url=\"https://example/media")
                    .append(i).append(".mp3\" type=\"audio/mpeg\" length=\"12345\"/>");
            sb.append("</item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    @Test
    void parsesArticlesFromRss() {
        itemCount = 3;
        Feed f = feeds.save(new Feed(url, "T", null));
        svc.refreshPublic(url);
        assertEquals(3, articles.findByFeedAndOwnerIsNull(f).size());
    }

    @Test
    void deduplicatesAcrossRepeatedFetches() {
        itemCount = 5;
        Feed f = feeds.save(new Feed(url, "T", null));
        svc.refreshPublic(url);
        svc.refreshPublic(url); // same links again
        assertEquals(5, articles.findByFeedAndOwnerIsNull(f).size(), "no duplicates on re-fetch");
    }

    @Test
    void parsesRssCategoryTagsIntoArticleCategories() {
        itemCount = 1;
        withCategories = true;
        Feed f = feeds.save(new Feed(url, "T", null));
        svc.refreshPublic(url);
        String cats = articles.findByFeedAndOwnerIsNull(f).get(0).getCategories();
        assertEquals("Tech, Business", cats, "article's own <category> tags, comma-joined (RSSOwl Category)");
    }

    @Test
    void parsesRssEnclosureIntoArticleAttachments() {
        itemCount = 1;
        withEnclosure = true;
        Feed f = feeds.save(new Feed(url, "T", null));
        svc.refreshPublic(url);
        com.example.headlines.data.Article a = articles.findByFeedAndOwnerIsNull(f).get(0);
        assertTrue(a.isAttachments(), "enclosure present -> attachments flag set");
        var encs = com.example.headlines.NewsItem.decodeEnclosures(a.getEnclosures());
        assertEquals(1, encs.size());
        assertEquals("https://example/media0.mp3", encs.get(0).url());
        assertEquals("audio/mpeg", encs.get(0).type());
        assertEquals(12345L, encs.get(0).length());
    }

    @Test
    void backfillsEnclosuresOnExistingArticleOnRefetch() {
        itemCount = 1;
        Feed f = feeds.save(new Feed(url, "T", null));
        withEnclosure = false;
        svc.refreshPublic(url); // stored before the feed advertises an enclosure
        assertTrue(com.example.headlines.NewsItem.decodeEnclosures(
                articles.findByFeedAndOwnerIsNull(f).get(0).getEnclosures()).isEmpty());
        withEnclosure = true;
        svc.refreshPublic(url); // same link now carries an enclosure -> backfilled, not duplicated
        var list = articles.findByFeedAndOwnerIsNull(f);
        assertEquals(1, list.size(), "no duplicate created");
        assertTrue(list.get(0).isAttachments());
        assertEquals(1, com.example.headlines.NewsItem.decodeEnclosures(list.get(0).getEnclosures()).size());
    }

    @Test
    void capsAtRssOwlsPerFeedLimitOf200() {
        itemCount = 250;
        Feed f = feeds.save(new Feed(url, "T", null));
        svc.refreshPublic(url);
        assertEquals(200, articles.findByFeedAndOwnerIsNull(f).size(), "RSSOwl's DEL_NEWS_BY_COUNT_VALUE");
    }
}
