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
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to RSSOwl's default feeds (mirrored in {@code feeds.opml} with current URLs) and
 * exposes their entries as {@link NewsItem}s. Fetches concurrently with per-feed timeouts; if every
 * feed fails (offline / CI), falls back to the {@link DemoData} fixture so the app always runs.
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    // RSSOwl keeps at most 200 news per feed by default (DEL_NEWS_BY_COUNT_VALUE = 200, with
    // count-based cleanup on) — see RSSOwl's PreferencesInitializer. We copy that as a PER-FEED cap.
    // MAX_ITEMS is then just a global memory safety net, not the real limit.
    private static final int PER_FEED_MAX = 200;
    private static final int MAX_ITEMS = 5000;
    private static final Duration PER_FEED_TIMEOUT = Duration.ofSeconds(8);
    private static final Map<String, String> CATEGORY_COLOR = Map.ofEntries(
            Map.entry("Business", "#1565c0"), Map.entry("Computers", "#37474f"),
            Map.entry("Entertainment", "#6a1b9a"), Map.entry("Food", "#ad1457"),
            Map.entry("Health", "#2e7d32"), Map.entry("Internet", "#0277bd"),
            Map.entry("Music", "#7b1fa2"), Map.entry("News", "#00695c"),
            Map.entry("Podcast", "#4527a0"), Map.entry("Politics", "#c62828"),
            Map.entry("Science", "#00838f"), Map.entry("Software", "#455a64"),
            Map.entry("Sports", "#ef6c00"), Map.entry("Technology", "#283593"),
            Map.entry("Weblogs", "#5d4037"));

    private record FeedSource(String title, String category, String url) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile List<NewsItem> items = List.of();
    private volatile boolean fallback = false;

    public List<NewsItem> items() { return items; }
    public boolean isFallback() { return fallback; }

    @PostConstruct
    void load() {
        List<FeedSource> sources = readOpml();
        List<NewsItem> all = new ArrayList<>();
        if (!sources.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(sources.size(), 12));
            List<Future<List<NewsItem>>> futures = new ArrayList<>();
            for (FeedSource s : sources) {
                Callable<List<NewsItem>> task = () -> fetch(s);
                futures.add(pool.submit(task));
            }
            for (int i = 0; i < futures.size(); i++) {
                FeedSource s = sources.get(i);
                try {
                    all.addAll(futures.get(i).get(PER_FEED_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("Feed failed, skipping: {} ({}) — {}", s.title(), s.url(), e.toString());
                }
            }
            pool.shutdownNow();
        }

        // De-duplicate by id (feeds repeat entries; TreeGrid rejects equal items).
        Map<Long, NewsItem> byId = new java.util.LinkedHashMap<>();
        for (NewsItem n : all) byId.putIfAbsent(n.id(), n);
        all = new ArrayList<>(byId.values());

        all.sort(Comparator.comparing(NewsItem::date,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (all.size() > MAX_ITEMS) {
            all = new ArrayList<>(all.subList(0, MAX_ITEMS));
        }

        if (all.isEmpty()) {
            log.warn("No feed items fetched — falling back to the bundled demo fixture.");
            this.items = DemoData.sample();
            this.fallback = true;
        } else {
            log.info("Loaded {} headlines from {} feed(s).", all.size(), sources.size());
            this.items = all;
            this.fallback = false;
        }
    }

    private List<NewsItem> fetch(FeedSource s) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.url()))
                .timeout(PER_FEED_TIMEOUT)
                .header("User-Agent", "headlines-poc/1.0 (+https://vaadin.com)")
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        List<NewsItem> out = new ArrayList<>();
        try (InputStream in = resp.body()) {
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(in));
            for (SyndEntry e : feed.getEntries()) {
                String link = e.getLink();
                if (link == null || link.isBlank()) continue;
                long id = hash64(s.title() + " " + link);
                String author = blankTo(e.getAuthor(), "Unknown");
                boolean hasAttachment = e.getEnclosures() != null && !e.getEnclosures().isEmpty();
                out.add(new NewsItem(
                        id,
                        blankTo(e.getTitle(), "(untitled)").strip(),
                        author,
                        s.category(),
                        s.title(),
                        toLocalDateTime(e.getPublishedDate() != null ? e.getPublishedDate() : e.getUpdatedDate()),
                        State.UNREAD,
                        false,
                        CATEGORY_COLOR.get(s.category()),
                        link,
                        hasAttachment));
            }
        }
        // Keep only the newest PER_FEED_MAX per feed, like RSSOwl's count-based cleanup.
        out.sort(Comparator.comparing(NewsItem::date, Comparator.nullsLast(Comparator.reverseOrder())));
        if (out.size() > PER_FEED_MAX) {
            out = new ArrayList<>(out.subList(0, PER_FEED_MAX));
        }
        return out;
    }

    private List<FeedSource> readOpml() {
        List<FeedSource> sources = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/feeds.opml")) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var doc = factory.newDocumentBuilder().parse(in);
            NodeList outlines = doc.getElementsByTagName("outline");
            for (int i = 0; i < outlines.getLength(); i++) {
                Element o = (Element) outlines.item(i);
                String xmlUrl = o.getAttribute("xmlUrl");
                if (xmlUrl == null || xmlUrl.isBlank()) continue; // folder, not a feed
                String title = firstNonBlank(o.getAttribute("title"), o.getAttribute("text"), "Feed");
                String category = "Uncategorized";
                Node parent = o.getParentNode();
                if (parent instanceof Element pe && "outline".equals(pe.getTagName())) {
                    category = firstNonBlank(pe.getAttribute("title"), pe.getAttribute("text"), category);
                }
                sources.add(new FeedSource(title, category, xmlUrl));
            }
        } catch (Exception e) {
            log.warn("Could not read feeds.opml: {}", e.toString());
        }
        return sources;
    }

    private static LocalDateTime toLocalDateTime(Date d) {
        return d == null ? null : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String blankTo(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static long hash64(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }
}