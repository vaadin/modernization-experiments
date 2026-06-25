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

import com.example.headlines.data.Article;
import com.example.headlines.data.ArticleRepository;
import com.example.headlines.data.Feed;
import com.example.headlines.data.FeedRepository;
import com.example.headlines.data.Subscription;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Global, shared feed layer for the multi-user app: seeds the {@link Feed} table from the bundled
 * defaults ({@link DefaultFeeds}) and fetches each feed's {@link Article}s into the database, once,
 * at startup. Feeds and articles are shared across all users; per-user state lives elsewhere
 * ({@code Subscription}, {@code ArticleState}). This replaces the in-memory list the single-user
 * {@code FeedService} kept. Network fetching is concurrent; persistence runs in one transaction.
 */
@Service
public class FeedFetchService {

    private static final Logger log = LoggerFactory.getLogger(FeedFetchService.class);
    // RSSOwl keeps at most 200 news per feed by default (DEL_NEWS_BY_COUNT_VALUE = 200).
    private static final int PER_FEED_MAX = 200;
    private static final Duration PER_FEED_TIMEOUT = Duration.ofSeconds(8);

    private final FeedRepository feeds;
    private final ArticleRepository articles;
    private final com.example.headlines.data.SubscriptionRepository subscriptions;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public FeedFetchService(FeedRepository feeds, ArticleRepository articles,
            com.example.headlines.data.SubscriptionRepository subscriptions) {
        this.feeds = feeds;
        this.articles = articles;
        this.subscriptions = subscriptions;
    }

    private record Raw(String link, String title, String author, LocalDateTime date, boolean attachments) {}

    @PostConstruct
    void init() {
        seedFeedsIfEmpty();
        refreshAll();
    }

    @Transactional
    void seedFeedsIfEmpty() {
        if (feeds.count() > 0) return;
        List<DefaultFeeds.Source> sources = DefaultFeeds.read();
        for (DefaultFeeds.Source s : sources) {
            if (feeds.findByUrl(s.url()).isEmpty()) {
                feeds.save(new Feed(s.url(), s.title(), s.category()));
            }
        }
        log.info("Seeded {} default feeds into the database.", feeds.count());
    }

    /** Fetch every feed (network, concurrent) then persist new articles (one transaction). */
    public void refreshAll() {
        List<Feed> all = feeds.findAll();
        if (all.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(all.size(), 12));
        try {
            List<Future<List<Raw>>> futures = new ArrayList<>();
            for (Feed f : all) {
                // An auth-gated feed is fetched with a subscriber's stored credentials (PoC: the first
                // credentialed subscription; in a single-user world this is just "the" credentials).
                Subscription cred = subscriptions.findFirstByFeedAndAuthUsernameIsNotNull(f).orElse(null);
                String user = cred != null ? cred.getAuthUsername() : null;
                String pass = cred != null ? cred.getAuthPassword() : null;
                futures.add(pool.submit(() -> fetchRaw(f, user, pass)));
            }
            int saved = 0;
            for (int i = 0; i < all.size(); i++) {
                Feed f = all.get(i);
                try {
                    saved += persist(f, futures.get(i).get(PER_FEED_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("Feed failed, skipping: {} ({}) — {}", f.getTitle(), f.getUrl(), e.toString());
                }
            }
            log.info("Feed refresh complete: {} new article(s) across {} feed(s).", saved, all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Fetch + persist a single feed now, using the given credentials (may be null) — used when a user
     * subscribes to a new feed or sets/updates its credentials. Propagates
     * {@link AuthenticationRequiredException} (HTTP 401) so the UI can prompt for login.
     */
    public void refreshByUrl(String url, String user, String pass) {
        feeds.findByUrl(url).ifPresent(f -> {
            try {
                persist(f, fetchRaw(f, user, pass));
            } catch (AuthenticationRequiredException auth) {
                throw auth; // let the caller turn this into a login prompt
            } catch (Exception e) {
                log.warn("Fetch failed for {}: {}", url, e.toString());
            }
        });
    }

    private List<Raw> fetchRaw(Feed f, String user, String pass) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(f.getUrl()))
                .timeout(PER_FEED_TIMEOUT)
                .header("User-Agent", "headlines-poc/1.0 (+https://vaadin.com)");
        if (user != null && !user.isBlank()) {
            String basic = java.util.Base64.getEncoder()
                    .encodeToString((user + ":" + (pass == null ? "" : pass)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + basic);
        }
        HttpResponse<InputStream> resp = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 401) {
            resp.body().close();
            String realm = resp.headers().firstValue("WWW-Authenticate")
                    .map(FeedFetchService::parseRealm).orElse(null);
            throw new AuthenticationRequiredException(f.getUrl(), realm);
        }
        List<Raw> out = new ArrayList<>();
        try (InputStream in = resp.body()) {
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(in));
            for (SyndEntry e : feed.getEntries()) {
                String link = e.getLink();
                if (link == null || link.isBlank()) continue;
                boolean att = e.getEnclosures() != null && !e.getEnclosures().isEmpty();
                out.add(new Raw(link, blankTo(e.getTitle(), "(untitled)").strip(),
                        blankTo(e.getAuthor(), "Unknown"),
                        toLocalDateTime(e.getPublishedDate() != null ? e.getPublishedDate() : e.getUpdatedDate()),
                        att));
            }
        }
        out.sort(Comparator.comparing(Raw::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > PER_FEED_MAX ? out.subList(0, PER_FEED_MAX) : out;
    }

    @Transactional
    int persist(Feed feed, List<Raw> raws) {
        int saved = 0;
        for (Raw r : raws) {
            if (articles.findByFeedAndLink(feed, r.link()).isEmpty()) {
                articles.save(new Article(feed, r.link(), r.title(), r.author(), r.date(), r.attachments()));
                saved++;
            }
        }
        feed.setLastFetched(Instant.now());
        feeds.save(feed);
        return saved;
    }

    private static LocalDateTime toLocalDateTime(Date d) {
        return d == null ? null : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String blankTo(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s;
    }

    /** Pull the realm out of a {@code WWW-Authenticate: Basic realm="..."} header, if present. */
    private static String parseRealm(String wwwAuthenticate) {
        var m = java.util.regex.Pattern.compile("realm=\"([^\"]*)\"").matcher(wwwAuthenticate);
        return m.find() ? m.group(1) : null;
    }
}
