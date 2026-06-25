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
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the bundled {@code feeds.opml} (RSSOwl's default-feeds taxonomy with current URLs) into a
 * flat list of {@link Source}s. Used both to seed the global {@code Feed} table and to seed a new
 * user's default {@code Subscription}s on first login. Extracted from the original single-user
 * {@code FeedService} so the OPML parsing lives in one place.
 */
public final class DefaultFeeds {

    private static final Logger log = LoggerFactory.getLogger(DefaultFeeds.class);

    /** A feed as declared in the OPML: its title, the folder it belongs to, and its URL. */
    public record Source(String title, String category, String url) {}

    private DefaultFeeds() {}

    public static List<Source> read() {
        List<Source> sources = new ArrayList<>();
        try (InputStream in = DefaultFeeds.class.getResourceAsStream("/feeds.opml")) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var doc = factory.newDocumentBuilder().parse(in);
            NodeList outlines = doc.getElementsByTagName("outline");
            for (int i = 0; i < outlines.getLength(); i++) {
                Element o = (Element) outlines.item(i);
                String xmlUrl = o.getAttribute("xmlUrl");
                if (xmlUrl == null || xmlUrl.isBlank()) continue; // a folder, not a feed
                String title = firstNonBlank(o.getAttribute("title"), o.getAttribute("text"), "Feed");
                String category = "Uncategorized";
                Node parent = o.getParentNode();
                if (parent instanceof Element pe && "outline".equals(pe.getTagName())) {
                    category = firstNonBlank(pe.getAttribute("title"), pe.getAttribute("text"), category);
                }
                sources.add(new Source(title, category, xmlUrl));
            }
        } catch (Exception e) {
            log.warn("Could not read feeds.opml: {}", e.toString());
        }
        return sources;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
