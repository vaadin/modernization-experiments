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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Reads the bundled {@code default_feeds.xml} — RSSOwl(nix)'s own first-run default-subscriptions OPML,
 * included verbatim (EPL-1.0, see NOTICE) so a new user's tree mirrors the original application exactly:
 * the same folders, sub-folders, and feeds. Used both to seed the global {@code Feed} table and to seed
 * a new user's default {@code Subscription}s on first login. Extracted from the original single-user
 * {@code FeedService} so the OPML parsing lives in one place.
 *
 * <p>{@link Source#category()} is the feed's full folder <em>path</em> — {@code "Computers/Windows"}
 * for a nested feed, a single segment for a top-level folder, or {@code "Uncategorized"} for a loose
 * channel directly under the OPML body.
 */
public final class DefaultFeeds {

    private static final Logger log = LoggerFactory.getLogger(DefaultFeeds.class);

    /** A feed as declared in the OPML: its title, the folder PATH it belongs to, and its URL. */
    public record Source(String title, String category, String url) {}

    private DefaultFeeds() {}

    public static List<Source> read() {
        List<Source> sources = new ArrayList<>();
        try (InputStream in = DefaultFeeds.class.getResourceAsStream("/default_feeds.xml")) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var doc = factory.newDocumentBuilder().parse(in);
            NodeList outlines = doc.getElementsByTagName("outline");
            for (int i = 0; i < outlines.getLength(); i++) {
                Element o = (Element) outlines.item(i);
                String xmlUrl = o.getAttribute("xmlUrl");
                if (xmlUrl == null || xmlUrl.isBlank()) continue; // a folder, not a feed
                String title = firstNonBlank(o.getAttribute("title"), o.getAttribute("text"), "Feed");
                String path = folderPathOf(o);
                sources.add(new Source(title, path.isEmpty() ? "Uncategorized" : path, xmlUrl));
            }
        } catch (Exception e) {
            log.warn("Could not read default_feeds.xml: {}", e.toString());
        }
        return sources;
    }

    /** A feed's folder path: its ancestor {@code <outline>} folder names, top-down, joined with
     *  {@code '/'}. Empty when the feed sits directly under the OPML body (a loose channel). */
    private static String folderPathOf(Element feedOutline) {
        Deque<String> segments = new ArrayDeque<>();
        for (Node p = feedOutline.getParentNode();
                p instanceof Element pe && "outline".equals(pe.getTagName()); p = p.getParentNode()) {
            String seg = firstNonBlank(pe.getAttribute("text"), pe.getAttribute("title"), "");
            if (!seg.isBlank()) segments.addFirst(seg);
        }
        return String.join("/", segments);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
