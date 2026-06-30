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
import com.example.headlines.data.Article;
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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fetch-level authentication tests for {@link FeedFetchService}, against a real (local) Basic-auth
 * HTTP server — the Vaadin equivalent of RSSOwl's {@code connection/ConnectionTests}, and the
 * regression guard for the per-user credential-isolation fix:
 * <ul>
 *   <li>the shared (anonymous) refresh of an auth-gated feed stores nothing;</li>
 *   <li>a per-user authenticated fetch stores articles private to that user (owner = subject);</li>
 *   <li>wrong credentials raise {@link AuthenticationRequiredException}.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaTestConfig.class)
@Transactional
class FeedFetchServiceAuthTest {

    private static final String RSS = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
            + "<title>Secret</title><item><title>SECRET ONE</title><link>https://e/1</link></item>"
            + "</channel></rss>";
    private static final String VALID = "Basic " + Base64.getEncoder()
            .encodeToString("u:p".getBytes(StandardCharsets.UTF_8));

    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;

    private FeedFetchService svc;
    private HttpServer server;
    private String url;

    @BeforeEach
    void setUp() throws IOException {
        svc = new FeedFetchService(feeds, articles, new ArticleSearchService(null), new FeedBroadcaster());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", ex -> {
            if (!VALID.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Secret\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] body = RSS.getBytes(StandardCharsets.UTF_8);
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

    @Test
    void anonymousRefreshOfAuthFeedStoresNothing() {
        Feed f = feeds.save(new Feed(url, "Secret", null));
        svc.refreshPublic(url); // no credentials -> 401 -> skipped
        assertEquals(0, articles.findByFeedAndOwnerIsNull(f).size());
    }

    @Test
    void authenticatedRefreshStoresArticlesPrivateToTheUser() {
        Feed f = feeds.save(new Feed(url, "Secret", null));
        svc.refreshForUser(url, "alice-subject", "u", "p");

        assertEquals(0, articles.findByFeedAndOwnerIsNull(f).size(), "nothing public");
        List<Article> alicePrivate = articles.findByFeedAndOwner(f, "alice-subject");
        assertEquals(1, alicePrivate.size(), "one article, private to alice");
        assertEquals("SECRET ONE", alicePrivate.get(0).getTitle());
    }

    @Test
    void wrongCredentialsRaiseAuthenticationRequired() {
        feeds.save(new Feed(url, "Secret", null));
        assertThrows(AuthenticationRequiredException.class,
                () -> svc.refreshForUser(url, "alice-subject", "u", "WRONG"));
    }
}
