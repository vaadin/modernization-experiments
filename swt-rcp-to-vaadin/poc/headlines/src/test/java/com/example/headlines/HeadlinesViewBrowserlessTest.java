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
import com.example.headlines.service.FeedFetchService;
import com.example.headlines.service.UserNewsService;
import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Browserless (in-JVM, no real browser) UI test of {@link HeadlinesView} using Vaadin's
 * {@code browserless-test-junit6} harness. Renders the view for a mocked authenticated user with
 * mocked services and asserts the three-pane structure + the signed-in identity. This is the tier
 * that can drive Vaadin components directly — including the {@code GridContextMenu} that a raw
 * Playwright synthetic right-click could not open.
 */
// Scan an empty package so the harness doesn't auto-navigate to HeadlinesView's "" route (which has
// no no-arg constructor). We add the view manually, built with mocked dependencies, instead.
@ViewPackages(packages = "com.example.headlines.noroutes")
class HeadlinesViewBrowserlessTest extends BrowserlessTest {

    private HeadlinesView buildViewForAlice() {
        OidcUser oidc = mock(OidcUser.class);
        when(oidc.getSubject()).thenReturn("alice-subject");
        when(oidc.getPreferredUsername()).thenReturn("alice");

        var auth = mock(com.vaadin.flow.spring.security.AuthenticationContext.class);
        when(auth.getAuthenticatedUser(OidcUser.class)).thenReturn(java.util.Optional.of(oidc));

        var news = mock(UserNewsService.class);
        when(news.feedRefs("alice-subject")).thenReturn(List.of(
                new UserNewsService.FeedRef(1L, "BBC News", null, 0, "https://bbc/rss", null)));
        when(news.newsItems("alice-subject")).thenReturn(List.of(
                new NewsItem(1, "Headline one", "Reporter", "Uncategorized", "BBC News",
                        LocalDateTime.of(2026, 1, 1, 0, 0), State.UNREAD, false, null, "https://x/1", false)));
        when(news.columnPrefs("alice-subject")).thenReturn(List.of()); // default layout, no saved prefs

        var fetch = mock(FeedFetchService.class);
        var broadcaster = new com.example.headlines.service.FeedBroadcaster();
        return new HeadlinesView(news, fetch, broadcaster, auth);
    }

    @Test
    void rendersThreePaneStructureForAuthenticatedUser() {
        UI.getCurrent().add(buildViewForAlice());

        // feeds tree + headlines tree
        assertEquals(2, $(TreeGrid.class).all().size(), "feeds tree + headlines grid");

        // signed-in identity in the header. The identity pill renders it as two spans — a
        // "Signed in as " label plus the bold user name — so assert both parts are present.
        var spanTexts = $(Span.class).all().stream().map(Span::getText)
                .filter(java.util.Objects::nonNull).toList();
        boolean signedInShown = spanTexts.stream().anyMatch(t -> t.startsWith("Signed in as"))
                && spanTexts.contains("alice");
        assertTrue(signedInShown, "header shows the authenticated user");

        // the RSSOwl-style actions are present
        var buttonTexts = $(Button.class).all().stream().map(Button::getText).toList();
        assertTrue(buttonTexts.contains("Add feed"), "Add feed button present");
        assertTrue(buttonTexts.contains("Log out"), "Log out button present");

        // the headlines grid uses custom (NONE-mode) desktop-style selection — no checkbox column, so
        // its model is NOT a GridMultiSelectionModel (selection is tracked by the view via item-click).
        boolean anyMulti = $(TreeGrid.class).all().stream()
                .anyMatch(g -> g.getSelectionModel() instanceof com.vaadin.flow.component.grid.GridMultiSelectionModel);
        assertFalse(anyMulti, "headlines grid uses custom click-based selection, not the checkbox model");
    }
}
