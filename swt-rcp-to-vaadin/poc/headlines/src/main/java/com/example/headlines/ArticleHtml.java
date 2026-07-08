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

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Sanitizes feed-supplied article HTML before it is rendered in the reader pane. Vaadin's
 * {@code Html} component injects raw markup without escaping, so untrusted feed content must be
 * cleaned first to prevent cross-site scripting (the Vaadin security docs recommend jsoup for exactly
 * this). RSSOwl rendered article HTML in an embedded SWT browser; here it is a cleaned fragment.
 */
public final class ArticleHtml {

    private ArticleHtml() {}

    /**
     * Clean {@code raw} to a safe subset (jsoup's "relaxed" allow-list: common formatting, links,
     * images, lists, tables — but no {@code <script>}/{@code <style>}, inline event handlers, or
     * {@code javascript:} URLs), and make any links open in a new tab with {@code rel="noopener"}.
     * Returns an empty string for null/blank input.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Safelist safelist = Safelist.relaxed().addAttributes("a", "target", "rel");
        org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(Jsoup.clean(raw, safelist));
        doc.select("a[href]").forEach(a -> a.attr("target", "_blank").attr("rel", "noopener noreferrer"));
        return doc.body().html();
    }

    /**
     * Strip {@code raw} HTML to plain visible text — used to build the full-text search index. Indexing
     * raw HTML breaks Lucene: a single unbroken run over 32,766 bytes (a {@code data:} image URI, minified
     * markup) exceeds Lucene's maximum term length and the whole document fails to index. Visible text
     * tokenizes into ordinary words, so it indexes cleanly and searches better (no tag noise).
     */
    public static String toPlainText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return Jsoup.parse(raw).text();
    }
}
