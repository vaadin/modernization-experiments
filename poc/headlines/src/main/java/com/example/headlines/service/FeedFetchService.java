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
import com.rometools.rome.feed.synd.SyndContent;
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
    private final ArticleSearchService search;
    private final FeedBroadcaster broadcaster;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Guards the shared full refresh so the periodic timer never overlaps the startup fetch (or itself).
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    public FeedFetchService(FeedRepository feeds, ArticleRepository articles, ArticleSearchService search,
            FeedBroadcaster broadcaster) {
        this.feeds = feeds;
        this.articles = articles;
        this.search = search;
        this.broadcaster = broadcaster;
    }

    private record Raw(String link, String title, String author, LocalDateTime date, boolean attachments,
            String content) {}

    @PostConstruct
    void init() {
        seedFeedsIfEmpty();
        search.ensureIndex(); // set up the full-text index + trigger BEFORE articles are inserted
        // The bundled defaults are RSSOwl's 2009 tree — ~292 feeds, most of them long dead. Fetching
        // them synchronously would stall startup on connect timeouts, so refresh in the background:
        // the app (and the feeds tree) come up immediately and article counts fill in as feeds resolve.
        Thread refresh = new Thread(this::refreshAll, "feed-initial-refresh");
        refresh.setDaemon(true);
        refresh.start();
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

    /**
     * Periodic background refresh — RSSOwl auto-refreshes feeds on a timer (its "Reload" interval).
     * Runs every {@code feeds.refresh-interval-ms} (default 15 min), the first run one interval after
     * startup (the {@code @PostConstruct} fetch already covers boot). The {@link #refreshing} guard means
     * a slow run is simply skipped rather than stacking up.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${feeds.refresh-interval-ms:900000}",
            initialDelayString = "${feeds.refresh-interval-ms:900000}")
    void scheduledRefresh() {
        log.info("Periodic feed refresh starting…");
        refreshAll();
    }

    /**
     * Shared refresh: fetch every feed <b>anonymously</b> (never with any user's stored credentials)
     * and store the articles as public (owner = null). Auth-gated feeds simply 401 here and are
     * skipped — their content is fetched per-user instead (see {@link #refreshForUser}).
     */
    public void refreshAll() {
        if (!refreshing.compareAndSet(false, true)) {
            log.info("Feed refresh already in progress — skipping this run.");
            return;
        }
        try {
            doRefreshAll();
        } finally {
            refreshing.set(false);
        }
    }

    private void doRefreshAll() {
        List<Feed> all = feeds.findAll();
        if (all.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(all.size(), 24));
        try {
            List<Future<List<Raw>>> futures = new ArrayList<>();
            for (Feed f : all) futures.add(pool.submit(() -> fetchRaw(f, null, null)));
            int saved = 0;
            for (int i = 0; i < all.size(); i++) {
                Feed f = all.get(i);
                try {
                    saved += persist(f, null, futures.get(i).get(PER_FEED_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("Feed failed, skipping: {} ({}) — {}", f.getTitle(), f.getUrl(), e.toString());
                }
            }
            log.info("Feed refresh complete: {} new public article(s) across {} feed(s).", saved, all.size());
            if (saved > 0) {
                broadcaster.broadcast(); // tell open sessions new articles arrived (live notification)
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Fetch + persist a public feed now (anonymous) — used when a user adds a feed without credentials. */
    public void refreshPublic(String url) {
        feeds.findByUrl(url).ifPresent(f -> {
            try {
                persist(f, null, fetchRaw(f, null, null));
            } catch (Exception e) {
                log.warn("Fetch failed for {}: {}", url, e.toString());
            }
        });
    }

    /**
     * Fetch + persist an authenticated feed for ONE user, with THEIR credentials, storing the articles
     * private to them (owner = subject) so no other user ever sees that content. Propagates
     * {@link AuthenticationRequiredException} (HTTP 401) so the UI can prompt for login.
     */
    public void refreshForUser(String url, String subject, String user, String pass) {
        feeds.findByUrl(url).ifPresent(f -> {
            try {
                persist(f, subject, fetchRaw(f, user, pass));
            } catch (AuthenticationRequiredException auth) {
                throw auth; // let the caller turn this into a login prompt
            } catch (Exception e) {
                log.warn("Fetch failed for {} (user {}): {}", url, subject, e.toString());
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
            SyndFeedInput input = new SyndFeedInput();
            // Many real feeds (incl. dozens of RSSOwl's 2009 defaults) carry a DOCTYPE; ROME rejects
            // those by default ("DOCTYPE is disallowed"). Allow them so we don't silently drop feeds —
            // external-entity resolution stays off, so this doesn't reopen the XXE hole.
            input.setAllowDoctypes(true);
            SyndFeed feed = input.build(new XmlReader(in));
            for (SyndEntry e : feed.getEntries()) {
                String link = e.getLink();
                if (link == null || link.isBlank()) continue;
                boolean att = e.getEnclosures() != null && !e.getEnclosures().isEmpty();
                out.add(new Raw(link, blankTo(e.getTitle(), "(untitled)").strip(),
                        e.getAuthor() == null ? "" : e.getAuthor().strip(), // leave blank when absent (RSSOwl-style)
                        toLocalDateTime(e.getPublishedDate() != null ? e.getPublishedDate() : e.getUpdatedDate()),
                        att, extractContent(e)));
            }
        }
        out.sort(Comparator.comparing(Raw::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > PER_FEED_MAX ? out.subList(0, PER_FEED_MAX) : out;
    }

    /** The article body the feed supplies: Atom {@code <content>} (preferred, fuller) else RSS
     *  {@code <description>}. Returned as raw HTML; sanitized only when rendered. */
    private static String extractContent(SyndEntry e) {
        if (e.getContents() != null && !e.getContents().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SyndContent c : e.getContents()) {
                if (c.getValue() != null) sb.append(c.getValue());
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        return e.getDescription() != null ? e.getDescription().getValue() : null;
    }

    /** Persist new articles for the given owner (null = public/shared), de-duplicated by (feed, link, owner). */
    @Transactional
    int persist(Feed feed, String owner, List<Raw> raws) {
        int saved = 0;
        for (Raw r : raws) {
            boolean exists = (owner == null)
                    ? articles.findByFeedAndLinkAndOwnerIsNull(feed, r.link()).isPresent()
                    : articles.findByFeedAndLinkAndOwner(feed, r.link(), owner).isPresent();
            if (!exists) {
                Article a = new Article(feed, owner, r.link(), r.title(), r.author(), r.date(), r.attachments());
                a.setContent(r.content());
                a.setContentText(com.example.headlines.ArticleHtml.toPlainText(r.content())); // for the FT index
                articles.save(a);
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
