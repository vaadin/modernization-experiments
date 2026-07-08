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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanitization tests for the reader's article HTML. The reader injects feed content via Vaadin's
 * {@code Html} (which does not escape), so the cleaning must strip anything executable.
 */
class ArticleHtmlTest {

    @Test
    void stripsScriptsAndEventHandlers() {
        String dirty = "<p>Hello</p><script>alert('xss')</script>"
                + "<img src=x onerror=\"alert(1)\"><a href=\"javascript:alert(2)\">x</a>";
        String clean = ArticleHtml.sanitize(dirty);

        assertTrue(clean.contains("<p>Hello</p>"), "safe markup is kept");
        assertFalse(clean.contains("<script"), "script tags removed");
        assertFalse(clean.contains("onerror"), "inline event handlers removed");
        assertFalse(clean.toLowerCase().contains("javascript:"), "javascript: URLs removed");
    }

    @Test
    void keepsFormattingAndOpensLinksInNewTab() {
        String clean = ArticleHtml.sanitize(
                "<p><strong>Bold</strong> and <a href=\"https://example.com\">link</a></p>");
        assertTrue(clean.contains("<strong>Bold</strong>"), "formatting preserved");
        assertTrue(clean.contains("href=\"https://example.com\""), "safe link preserved");
        assertTrue(clean.contains("target=\"_blank\""), "links open in a new tab");
        assertTrue(clean.contains("rel=\"noopener noreferrer\""), "links carry rel=noopener");
    }

    @Test
    void blankInputYieldsEmptyString() {
        assertEquals("", ArticleHtml.sanitize(null));
        assertEquals("", ArticleHtml.sanitize("   "));
    }
}
