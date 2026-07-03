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
import com.example.headlines.NewsItem;
import com.example.headlines.data.Article;
import com.example.headlines.data.ArticleRepository;
import com.example.headlines.data.ArticleStateRepository;
import com.example.headlines.data.Feed;
import com.example.headlines.data.FeedRepository;
import com.example.headlines.data.Subscription;
import com.example.headlines.data.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-layer tests for {@link UserNewsService} on an in-memory JPA slice (no security, no network).
 * These cover the multi-user guarantees that matter most — per-user article visibility and read state —
 * the Vaadin equivalent of RSSOwl's headless model/controller tests.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaTestConfig.class)
@Transactional
class UserNewsServiceTest {

    private static final String ALICE = "alice-subject";
    private static final String BOB = "bob-subject";

    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired ArticleStateRepository states;
    @Autowired com.example.headlines.data.FolderPrefRepository folderPrefs;
    @Autowired com.example.headlines.data.ColumnPrefRepository columnPrefs;
    @Autowired com.example.headlines.data.UserStateRepository userStates;
    @Autowired com.example.headlines.data.NewsFilterRepository newsFilters;
    @Autowired com.example.headlines.data.LabelRepository labelRepo;
    @Autowired com.example.headlines.data.SavedSearchRepository savedSearches;
    @Autowired com.example.headlines.data.NewsBinRepository newsBins;

    private UserNewsService svc;
    // Stub full-text search: returns a fixed set of article IDs, so search() scoping is testable
    // without standing up the Lucene index.
    private final List<Long> searchHits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ArticleSearch fakeSearch = (q, limit) -> List.copyOf(searchHits);
        svc = new UserNewsService(feeds, articles, subscriptions, states, folderPrefs, columnPrefs,
                userStates, newsFilters, labelRepo, savedSearches, newsBins, fakeSearch);
    }

    @Test
    void privateArticlesAreIsolatedPerUser() {
        Feed f = feeds.save(new Feed("https://secret/feed", "Secret", null));
        articles.save(new Article(f, null, "https://x/pub", "Public item", "a", LocalDateTime.now(), false));
        articles.save(new Article(f, ALICE, "https://x/priv", "Alice private", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));
        subscriptions.save(new Subscription(BOB, f, null, 0));

        List<String> aliceTitles = svc.newsItems(ALICE).stream().map(NewsItem::title).toList();
        List<String> bobTitles = svc.newsItems(BOB).stream().map(NewsItem::title).toList();

        assertTrue(aliceTitles.contains("Public item"));
        assertTrue(aliceTitles.contains("Alice private"));
        assertTrue(bobTitles.contains("Public item"), "public articles are shared");
        assertFalse(bobTitles.contains("Alice private"), "bob must NOT see alice's private article");
    }

    @Test
    void readStateIsPerUserAndPersists() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article a = articles.save(new Article(f, null, "https://x/1", "Item", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));
        subscriptions.save(new Subscription(BOB, f, null, 0));

        svc.setRead(ALICE, a.getId(), true);

        assertFalse(svc.newsItems(ALICE).get(0).unread(), "alice marked it read");
        assertTrue(svc.newsItems(BOB).get(0).unread(), "bob is unaffected (isolated state)");
    }

    @Test
    void toggleReadFlipsStatePersistsAndIsPerUser() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article a = articles.save(new Article(f, null, "https://x/1", "Item", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));
        subscriptions.save(new Subscription(BOB, f, null, 0));

        // Backs the keyboard Enter action. Starts unread → first toggle marks it read.
        assertTrue(svc.toggleRead(ALICE, a.getId()), "unread → read returns the new state (read)");
        assertFalse(svc.newsItems(ALICE).get(0).unread(), "alice's item is now read (persisted)");
        assertTrue(svc.newsItems(BOB).get(0).unread(), "bob is unaffected (per-user isolation)");

        // Toggling again marks it unread.
        assertFalse(svc.toggleRead(ALICE, a.getId()), "read → unread returns the new state (unread)");
        assertTrue(svc.newsItems(ALICE).get(0).unread(), "alice's item is unread again");
    }

    @Test
    void readDelayMsDefaultsAndPersistsPerUserIncludingSentinels() {
        assertEquals(UserNewsService.DEFAULT_READ_DELAY_MS, svc.readDelayMs(ALICE),
                "auto-read delay defaults to 0.5s when the user hasn't chosen one");

        svc.setReadDelayMs(ALICE, 2000);
        assertEquals(2000, svc.readDelayMs(ALICE), "alice's chosen delay persists");
        assertEquals(UserNewsService.DEFAULT_READ_DELAY_MS, svc.readDelayMs(BOB),
                "bob keeps the default (per-user)");

        svc.setReadDelayMs(ALICE, -1); // Off
        assertEquals(-1, svc.readDelayMs(ALICE), "off (-1) round-trips");
        svc.setReadDelayMs(ALICE, 0);  // Instant
        assertEquals(0, svc.readDelayMs(ALICE), "instant (0) round-trips");
    }

    @Test
    void labelsArePerUserAndExposedOnNewsItem() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article a = articles.save(new Article(f, null, "https://x/1", "Item", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));
        subscriptions.save(new Subscription(BOB, f, null, 0));

        long important = svc.createLabel(ALICE, "Important", "#c62828");
        svc.setLabels(ALICE, a.getId(), java.util.Set.of(important));

        NewsItem aliceItem = svc.newsItems(ALICE).get(0);
        assertEquals(1, aliceItem.labels().size());
        assertEquals("Important", aliceItem.labels().get(0).name());
        assertEquals("#c62828", aliceItem.labelColor(), "derived colour = first label");
        assertTrue(svc.newsItems(BOB).get(0).labels().isEmpty(), "bob sees no labels (isolated)");
    }

    @Test
    void multipleLabelsPerItemAndDeleteRemovesFromItems() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article a = articles.save(new Article(f, null, "https://x/1", "Item", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));

        long work = svc.createLabel(ALICE, "Work", "#1565c0");
        long todo = svc.createLabel(ALICE, "To Do", "#ef6c00");
        svc.setLabels(ALICE, a.getId(), java.util.Set.of(work, todo));
        assertEquals(2, svc.newsItems(ALICE).get(0).labels().size(), "two labels on one item");

        svc.deleteLabel(ALICE, work);
        var remaining = svc.newsItems(ALICE).get(0).labels();
        assertEquals(1, remaining.size(), "deleting a label removes it from the item");
        assertEquals("To Do", remaining.get(0).name());
    }

    @Test
    void newsBinHoldsItemsIsOwnerScopedAndRemovable() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article a1 = articles.save(new Article(f, null, "https://x/1", "Public one", "a", LocalDateTime.now(), false));
        Article a2 = articles.save(new Article(f, null, "https://x/2", "Public two", "a", LocalDateTime.now(), false));
        Article bobPriv = articles.save(new Article(f, BOB, "https://x/bp", "Bob private", "a", LocalDateTime.now(), false));

        long bin = svc.createBin(ALICE, "Keep");
        svc.addToBin(ALICE, bin, List.of(a1.getId(), a2.getId(), bobPriv.getId()));

        var titles = svc.binItems(ALICE, bin).stream().map(NewsItem::title).toList();
        assertEquals(2, titles.size(), "only the 2 visible (public) articles surface");
        assertTrue(titles.contains("Public one") && titles.contains("Public two"));
        assertFalse(titles.contains("Bob private"), "bin never exposes another user's private article");
        assertEquals(3, svc.bins(ALICE).get(0).count(), "count is stored ids (incl. the non-visible one)");

        svc.removeFromBin(ALICE, bin, List.of(a1.getId()));
        assertFalse(svc.binItems(ALICE, bin).stream().anyMatch(n -> n.title().equals("Public one")), "removed");

        // bob can't see or delete alice's bin
        assertTrue(svc.bins(BOB).isEmpty());
        svc.deleteBin(BOB, bin);
        assertEquals(1, svc.bins(ALICE).size(), "another user can't delete it");
        svc.deleteBin(ALICE, bin);
        assertTrue(svc.bins(ALICE).isEmpty());
    }

    @Test
    void savedSearchesAreCrudAndPerUser() {
        long id = svc.createSavedSearch(ALICE, "Linux stuff", "linux OR kernel");
        var aliceSearches = svc.savedSearches(ALICE);
        assertEquals(1, aliceSearches.size());
        assertEquals("Linux stuff", aliceSearches.get(0).name());
        assertEquals("linux OR kernel", aliceSearches.get(0).query());
        assertTrue(svc.savedSearches(BOB).isEmpty(), "bob has none (per-user)");

        svc.deleteSavedSearch(BOB, id); // wrong owner — must not delete alice's
        assertEquals(1, svc.savedSearches(ALICE).size(), "another user can't delete it");

        svc.deleteSavedSearch(ALICE, id);
        assertTrue(svc.savedSearches(ALICE).isEmpty(), "owner can delete");
    }

    @Test
    void ensureLabelsSeedsTheFiveDefaultsOnce() {
        assertEquals(5, svc.ensureLabels(ALICE).size(), "RSSOwl's five default labels seeded");
        assertEquals(5, svc.ensureLabels(ALICE).size(), "idempotent — not re-seeded");
        assertTrue(svc.labels(BOB).isEmpty(), "bob not seeded until his own first use");
    }

    @Test
    void setCredentialsStoredAndExposedViaFeedRef() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Subscription s = subscriptions.save(new Subscription(ALICE, f, null, 0));

        svc.setCredentials(ALICE, s.getId(), "user", "pw");

        assertEquals("user", svc.feedRefs(ALICE).get(0).authUsername());
    }

    @Test
    void ensureSeededGivesNewUserTheDefaultSubscriptions() {
        // Seed the global Feed table from the bundled defaults, then seed alice from them.
        var defaults = DefaultFeeds.read();
        defaults.forEach(s -> feeds.save(new Feed(s.url(), s.title(), s.category())));

        svc.ensureSeeded(ALICE);
        int afterFirst = svc.feedRefs(ALICE).size();
        svc.ensureSeeded(ALICE); // idempotent — must not double-subscribe

        assertEquals(defaults.size(), afterFirst, "one subscription per default feed");
        assertEquals(afterFirst, svc.feedRefs(ALICE).size(), "ensureSeeded is idempotent");
    }

    @Test
    void addAndRemoveSubscription() {
        svc.addSubscription(ALICE, "https://new/feed", "New Feed", "News", null, null);
        assertEquals(1, svc.feedRefs(ALICE).size());

        long subId = svc.feedRefs(ALICE).get(0).subscriptionId();
        svc.removeSubscription(ALICE, subId);
        assertTrue(svc.feedRefs(ALICE).isEmpty(), "unsubscribed");
    }

    @Test
    void removeSubscriptionIsScopedToOwner() {
        Feed f = feeds.save(new Feed("https://f", "F", null));
        Subscription bobs = subscriptions.save(new Subscription(BOB, f, null, 0));
        // alice must not be able to remove bob's subscription
        svc.removeSubscription(ALICE, bobs.getId());
        assertEquals(1, svc.feedRefs(BOB).size(), "bob's subscription survives alice's call");
    }

    @Test
    void reorderFoldersPersistsFolderOrder() {
        svc.reorderFolders(ALICE, List.of("News", "Business", "Science"));
        assertEquals(List.of("News", "Business", "Science"), svc.folderOrder(ALICE));

        // re-ordering again updates positions (idempotent upsert, no duplicates)
        svc.reorderFolders(ALICE, List.of("Science", "News", "Business"));
        assertEquals(List.of("Science", "News", "Business"), svc.folderOrder(ALICE));
    }

    @Test
    void reorderFolderPersistsOrderAndFolder() {
        Feed f1 = feeds.save(new Feed("https://a", "A", null));
        Feed f2 = feeds.save(new Feed("https://b", "B", null));
        Subscription s1 = subscriptions.save(new Subscription(ALICE, f1, null, 0));
        Subscription s2 = subscriptions.save(new Subscription(ALICE, f2, null, 1));

        // Put them into "News" in reversed order.
        svc.reorderFolder(ALICE, "News", List.of(s2.getId(), s1.getId()));

        List<UserNewsService.FeedRef> refs = svc.feedRefs(ALICE);
        assertEquals(List.of("B", "A"), refs.stream().map(UserNewsService.FeedRef::title).toList());
        assertTrue(refs.stream().allMatch(r -> "News".equals(r.folder())));
    }

    @Test
    void applyFiltersActionsMatchingItemsAndIsPerUser() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article linux = articles.save(new Article(f, null, "https://x/1", "Linux kernel 6.14", "a", LocalDateTime.now(), false));
        Article food = articles.save(new Article(f, null, "https://x/2", "Best pasta recipe", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));
        subscriptions.save(new Subscription(BOB, f, null, 0));

        // Alice's filter: Title contains "linux" -> mark read.
        var def = new UserNewsService.FilterDef(null, "Linux→read", true,
                com.example.headlines.data.NewsFilter.MatchMode.ALL,
                List.of(new com.example.headlines.data.NewsFilter.Condition(
                        com.example.headlines.data.NewsFilter.Field.TITLE, "linux")),
                List.of("MARK_READ"));
        svc.saveFilter(ALICE, def);

        int applied = svc.applyFilters(ALICE);
        assertEquals(1, applied, "only the matching article is actioned");

        var aliceByTitle = svc.newsItems(ALICE).stream()
                .collect(java.util.stream.Collectors.toMap(NewsItem::title, n -> n));
        assertFalse(aliceByTitle.get("Linux kernel 6.14").unread(), "matched item marked read");
        assertTrue(aliceByTitle.get("Best pasta recipe").unread(), "non-matching item untouched");

        // Bob has no such filter and shares the feed — his state is unaffected (per-user isolation).
        assertTrue(svc.newsItems(BOB).stream().allMatch(NewsItem::unread), "bob's items all still unread");
    }

    @Test
    void applyFiltersIsIdempotent() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        articles.save(new Article(f, null, "https://x/1", "Linux kernel", "a", LocalDateTime.now(), false));
        subscriptions.save(new Subscription(ALICE, f, null, 0));
        svc.saveFilter(ALICE, new UserNewsService.FilterDef(null, "f", true,
                com.example.headlines.data.NewsFilter.MatchMode.ALL,
                List.of(new com.example.headlines.data.NewsFilter.Condition(
                        com.example.headlines.data.NewsFilter.Field.TITLE, "linux")),
                List.of("MARK_READ")));

        assertEquals(1, svc.applyFilters(ALICE), "first run marks it read");
        assertEquals(0, svc.applyFilters(ALICE), "second run is a no-op (additive/idempotent)");
    }

    @Test
    void lastSeenStartsNullAndIsRecordedPerUser() {
        org.junit.jupiter.api.Assertions.assertNull(svc.lastSeen(ALICE), "no visit recorded yet");
        svc.markSeen(ALICE);
        org.junit.jupiter.api.Assertions.assertNotNull(svc.lastSeen(ALICE), "alice's visit recorded");
        org.junit.jupiter.api.Assertions.assertNull(svc.lastSeen(BOB), "bob unaffected (per-user)");
    }

    @Test
    void searchIsScopedToOwner_neverLeaksAnotherUsersPrivateArticle() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article pub = articles.save(new Article(f, null, "https://x/pub", "Public hit", "a", LocalDateTime.now(), false));
        Article alicePriv = articles.save(new Article(f, ALICE, "https://x/ap", "Alice hit", "a", LocalDateTime.now(), false));
        Article bobPriv = articles.save(new Article(f, BOB, "https://x/bp", "Bob hit", "a", LocalDateTime.now(), false));

        // The (stubbed) index matches all three; scoping must drop bob's private article for alice.
        searchHits.addAll(List.of(pub.getId(), alicePriv.getId(), bobPriv.getId()));

        List<String> titles = svc.search(ALICE, "hit").stream().map(NewsItem::title).toList();
        assertTrue(titles.contains("Public hit"), "public article is searchable");
        assertTrue(titles.contains("Alice hit"), "alice finds her own private article");
        assertFalse(titles.contains("Bob hit"), "alice must NOT find bob's private article");
    }

    @Test
    void searchPreservesRelevanceOrderOfTheIndex() {
        Feed f = feeds.save(new Feed("https://feed", "F", null));
        Article a1 = articles.save(new Article(f, null, "https://x/1", "first", "a", LocalDateTime.now(), false));
        Article a2 = articles.save(new Article(f, null, "https://x/2", "second", "a", LocalDateTime.now(), false));
        searchHits.addAll(List.of(a2.getId(), a1.getId())); // index ranks a2 before a1

        assertEquals(List.of("second", "first"),
                svc.search(ALICE, "x").stream().map(NewsItem::title).toList(), "Lucene rank order kept");
    }

    @Test
    void columnLayoutPersistsOrderWidthAndVisibility() {
        svc.saveColumnLayout(ALICE, List.of(
                new UserNewsService.ColumnState("title", 0, null, true),
                new UserNewsService.ColumnState("date", 1, "200px", true),
                new UserNewsService.ColumnState("author", 2, null, false))); // hidden

        List<UserNewsService.ColumnState> saved = svc.columnPrefs(ALICE);
        assertEquals(List.of("title", "date", "author"),
                saved.stream().map(UserNewsService.ColumnState::key).toList(), "stored in order");
        assertEquals("200px", saved.get(1).width(), "date keeps its resized width");
        assertFalse(saved.get(2).visible(), "author stays hidden");
    }

    @Test
    void savingColumnLayoutAgainUpsertsWithoutDuplicates() {
        svc.saveColumnLayout(ALICE, List.of(
                new UserNewsService.ColumnState("author", 0, null, true),
                new UserNewsService.ColumnState("title", 1, null, true)));
        // Reorder + hide title on a second save.
        svc.saveColumnLayout(ALICE, List.of(
                new UserNewsService.ColumnState("title", 0, null, false),
                new UserNewsService.ColumnState("author", 1, null, true)));

        List<UserNewsService.ColumnState> saved = svc.columnPrefs(ALICE);
        assertEquals(2, saved.size(), "upsert, not insert — no duplicate rows per column");
        assertEquals(List.of("title", "author"),
                saved.stream().map(UserNewsService.ColumnState::key).toList());
        assertFalse(saved.get(0).visible(), "title now hidden");
    }

    @Test
    void columnLayoutIsPerUser() {
        svc.saveColumnLayout(ALICE, List.of(new UserNewsService.ColumnState("author", 0, null, false)));
        assertFalse(svc.columnPrefs(ALICE).get(0).visible(), "alice hid author");
        assertTrue(svc.columnPrefs(BOB).isEmpty(), "bob keeps the default layout (isolated)");
    }
}
