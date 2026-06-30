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

import com.example.headlines.NewsItem;
import com.example.headlines.NewsItem.State;
import com.example.headlines.data.Article;
import com.example.headlines.data.ArticleRepository;
import com.example.headlines.data.ArticleState;
import com.example.headlines.data.ArticleStateRepository;
import com.example.headlines.data.Feed;
import com.example.headlines.data.FeedRepository;
import com.example.headlines.data.Subscription;
import com.example.headlines.data.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-user view of the shared feed data, keyed by Keycloak subject. Seeds a new user's
 * {@link Subscription}s from the defaults on first login, merges each subscribed feed's
 * {@link Article}s with the user's {@link ArticleState} into {@link NewsItem}s for the grid, and
 * persists per-user mutations (read/sticky/label, feed order/folder, add/remove subscription).
 *
 * <p>Methods return plain DTOs / {@code NewsItem}s rather than managed entities, so callers (the
 * Vaadin view) are safe with {@code spring.jpa.open-in-view=false}.
 */
@Service
public class UserNewsService {

    /** What the feeds tree needs about one subscription, detached from JPA lazy state.
     *  {@code authUsername} is non-null when the feed has stored HTTP credentials. */
    public record FeedRef(long subscriptionId, String title, String folder, int position,
            String url, String authUsername) {}

    /** One headlines-grid column's saved layout, detached from JPA. {@code width} is null when the
     *  column is left to flex/auto-size. */
    public record ColumnState(String key, int position, String width, boolean visible) {}

    /** A news filter, detached from JPA for the view: its rules and actions. {@code id} is null for a
     *  not-yet-saved filter. */
    public record FilterDef(Long id, String name, boolean enabled,
            com.example.headlines.data.NewsFilter.MatchMode matchMode,
            List<com.example.headlines.data.NewsFilter.Condition> conditions, List<String> actions) {}

    private final FeedRepository feeds;
    private final ArticleRepository articles;
    private final SubscriptionRepository subscriptions;
    private final ArticleStateRepository states;
    private final com.example.headlines.data.FolderPrefRepository folderPrefs;
    private final com.example.headlines.data.ColumnPrefRepository columnPrefs;
    private final com.example.headlines.data.UserStateRepository userStates;
    private final com.example.headlines.data.NewsFilterRepository newsFilters;
    private final ArticleSearch articleSearch;

    public UserNewsService(FeedRepository feeds, ArticleRepository articles,
            SubscriptionRepository subscriptions, ArticleStateRepository states,
            com.example.headlines.data.FolderPrefRepository folderPrefs,
            com.example.headlines.data.ColumnPrefRepository columnPrefs,
            com.example.headlines.data.UserStateRepository userStates,
            com.example.headlines.data.NewsFilterRepository newsFilters,
            ArticleSearch articleSearch) {
        this.feeds = feeds;
        this.articles = articles;
        this.subscriptions = subscriptions;
        this.states = states;
        this.folderPrefs = folderPrefs;
        this.columnPrefs = columnPrefs;
        this.userStates = userStates;
        this.newsFilters = newsFilters;
        this.articleSearch = articleSearch;
    }

    /** When the user last opened the app (null on their first visit). */
    @Transactional(readOnly = true)
    public java.time.LocalDateTime lastSeen(String subject) {
        return userStates.findByOwner(subject)
                .map(com.example.headlines.data.UserState::getLastSeen).orElse(null);
    }

    /** Record "the user has now seen the app" — used to compute new-since-last-visit next time. */
    @Transactional
    public void markSeen(String subject) {
        com.example.headlines.data.UserState st = userStates.findByOwner(subject)
                .orElseGet(() -> new com.example.headlines.data.UserState(subject));
        st.setLastSeen(java.time.LocalDateTime.now());
        userStates.save(st);
    }

    /** First-login bootstrap: give a brand-new user the default subscription set (from default_feeds.xml). */
    @Transactional
    public void ensureSeeded(String subject) {
        if (subscriptions.existsByOwner(subject)) return;
        int pos = 0;
        for (DefaultFeeds.Source s : DefaultFeeds.read()) {
            Feed feed = feeds.findByUrl(s.url()).orElse(null);
            if (feed == null) continue;
            String folder = "Uncategorized".equals(s.category()) ? null : s.category();
            subscriptions.save(new Subscription(subject, feed, folder, pos++));
        }
    }

    @Transactional(readOnly = true)
    public List<FeedRef> feedRefs(String subject) {
        return subscriptions.findByOwnerOrderByFolderAscPositionAsc(subject).stream()
                .map(s -> new FeedRef(s.getId(), s.displayTitle(), s.getFolder(), s.getPosition(),
                        s.getFeed().getUrl(), s.getAuthUsername()))
                .toList();
    }

    /** All of the user's headlines (their subscribed feeds' articles merged with their state). */
    @Transactional(readOnly = true)
    public List<NewsItem> newsItems(String subject) {
        List<Subscription> subs = subscriptions.findByOwnerOrderByFolderAscPositionAsc(subject);
        Map<Long, ArticleState> stateByArticle = states.findByOwner(subject).stream()
                .collect(Collectors.toMap(st -> st.getArticle().getId(), Function.identity(), (a, b) -> a));
        List<NewsItem> out = new ArrayList<>();
        for (Subscription sub : subs) {
            String folder = (sub.getFolder() == null || sub.getFolder().isBlank())
                    ? "Uncategorized" : sub.getFolder();
            // The articles this user may see for the feed: public ones + their own private copies.
            // Never another user's private articles.
            List<Article> visible = new ArrayList<>(articles.findByFeedAndOwnerIsNull(sub.getFeed()));
            visible.addAll(articles.findByFeedAndOwner(sub.getFeed(), subject));
            for (Article a : visible) {
                ArticleState st = stateByArticle.get(a.getId());
                boolean read = st != null && st.isRead();
                boolean sticky = st != null && st.isSticky();
                String label = st != null ? st.getLabelColor() : null;
                NewsItem item = new NewsItem(a.getId(), a.getTitle(), a.getAuthor(), folder, sub.displayTitle(),
                        a.getPublishedDate(), read ? State.READ : State.UNREAD, sticky, label,
                        a.getLink(), a.isAttachments());
                item.setContent(a.getContent());
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Full-text search across the whole archive (title + body + author), Lucene-ranked, then merged
     * with this user's read/sticky/label state. <b>Security:</b> results are scoped to what the user may
     * see — public articles (owner null) plus their own private ones — so a query never surfaces another
     * user's private content, even if it matches.
     */
    @Transactional(readOnly = true)
    public List<NewsItem> search(String subject, String query) {
        List<Long> ranked = articleSearch.searchIds(query, 200);
        if (ranked.isEmpty()) return List.of();

        Map<Long, ArticleState> stateByArticle = states.findByOwner(subject).stream()
                .collect(Collectors.toMap(st -> st.getArticle().getId(), Function.identity(), (a, b) -> a));
        Map<Long, Article> byId = articles.findAllById(ranked).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity(), (a, b) -> a));

        List<NewsItem> out = new ArrayList<>();
        for (Long id : ranked) { // keep Lucene relevance order
            Article a = byId.get(id);
            if (a == null) continue;
            String owner = a.getOwner();
            if (owner != null && !owner.equals(subject)) continue; // never another user's private article
            ArticleState st = stateByArticle.get(id);
            boolean read = st != null && st.isRead();
            boolean sticky = st != null && st.isSticky();
            String label = st != null ? st.getLabelColor() : null;
            String cat = a.getFeed().getDefaultCategory();
            NewsItem item = new NewsItem(a.getId(), a.getTitle(), a.getAuthor(),
                    (cat == null || cat.isBlank()) ? "Uncategorized" : cat, a.getFeed().getTitle(),
                    a.getPublishedDate(), read ? State.READ : State.UNREAD, sticky, label,
                    a.getLink(), a.isAttachments());
            item.setContent(a.getContent());
            out.add(item);
        }
        return out;
    }

    // --- news filters (RSSOwl's rules engine: match conditions -> actions), per user ---

    /** The user's filters, in order, as detached DTOs. */
    @Transactional(readOnly = true)
    public List<FilterDef> filters(String subject) {
        return newsFilters.findByOwnerOrderByPositionAsc(subject).stream()
                .map(f -> new FilterDef(f.getId(), f.getName(), f.isEnabled(), f.getMatchMode(),
                        new ArrayList<>(f.getConditions()), new ArrayList<>(f.getActions())))
                .toList();
    }

    /** Create or update a filter for the user (owner enforced). Returns the saved id. */
    @Transactional
    public long saveFilter(String subject, FilterDef def) {
        com.example.headlines.data.NewsFilter f;
        if (def.id() == null) {
            f = new com.example.headlines.data.NewsFilter(subject, def.name());
            f.setPosition(newsFilters.findByOwnerOrderByPositionAsc(subject).size());
        } else {
            f = newsFilters.findById(def.id()).filter(x -> x.getOwner().equals(subject)).orElseThrow();
            f.setName(def.name());
        }
        f.setEnabled(def.enabled());
        f.setMatchMode(def.matchMode());
        // Rebuild the collections with fresh instances (don't share references across entities).
        List<com.example.headlines.data.NewsFilter.Condition> conds = new ArrayList<>();
        for (var c : def.conditions()) {
            if (c.getValue() != null && !c.getValue().isBlank()) {
                conds.add(new com.example.headlines.data.NewsFilter.Condition(c.getField(), c.getValue().trim()));
            }
        }
        f.setConditions(conds);
        f.setActions(new ArrayList<>(def.actions()));
        return newsFilters.save(f).getId();
    }

    @Transactional
    public void deleteFilter(String subject, long filterId) {
        newsFilters.findById(filterId).filter(f -> f.getOwner().equals(subject)).ifPresent(newsFilters::delete);
    }

    /**
     * Run the user's enabled filters over their current news and apply matching actions — RSSOwl's
     * filter behaviour. Actions are <b>additive/idempotent</b>: mark-read never un-reads, make-sticky
     * never un-stickies, label only changes a differing label, so re-running (or auto-run on open) never
     * thrashes a user's manual choices. Returns the number of actions actually applied.
     */
    @Transactional
    public int applyFilters(String subject) {
        List<com.example.headlines.data.NewsFilter> active = newsFilters.findByOwnerOrderByPositionAsc(subject)
                .stream().filter(com.example.headlines.data.NewsFilter::isEnabled).toList();
        if (active.isEmpty()) return 0;
        int applied = 0;
        for (NewsItem item : newsItems(subject)) {
            for (com.example.headlines.data.NewsFilter f : active) {
                if (!FilterEngine.matches(f, item)) continue;
                for (String action : f.getActions()) {
                    if ("MARK_READ".equals(action) && item.unread()) {
                        setRead(subject, item.id(), true); applied++;
                    } else if ("MARK_STICKY".equals(action) && !item.sticky()) {
                        setSticky(subject, item.id(), true); applied++;
                    } else if (action.startsWith("LABEL:")) {
                        String color = action.substring("LABEL:".length());
                        if (!color.equals(item.labelColor())) { setLabel(subject, item.id(), color); applied++; }
                    }
                }
            }
        }
        return applied;
    }

    // --- per-article state mutations (lazy upsert: a row is created only when state diverges) ---

    @Transactional
    public void setRead(String subject, long articleId, boolean read) {
        upsert(subject, articleId, st -> st.setRead(read));
    }

    @Transactional
    public void setSticky(String subject, long articleId, boolean sticky) {
        upsert(subject, articleId, st -> st.setSticky(sticky));
    }

    @Transactional
    public void setLabel(String subject, long articleId, String labelColor) {
        upsert(subject, articleId, st -> st.setLabelColor(labelColor));
    }

    private void upsert(String subject, long articleId, java.util.function.Consumer<ArticleState> mutator) {
        Article a = articles.findById(articleId).orElseThrow();
        ArticleState st = states.findByOwnerAndArticle(subject, a)
                .orElseGet(() -> new ArticleState(subject, a));
        mutator.accept(st);
        states.save(st);
    }

    // --- subscription mutations (feeds tree: reorder / move folder / add / remove) ---

    /** The user's saved folder order (category names by position); empty if they never reordered. */
    @Transactional(readOnly = true)
    public List<String> folderOrder(String subject) {
        return folderPrefs.findByOwnerOrderByPositionAsc(subject).stream()
                .map(com.example.headlines.data.FolderPref::getName)
                .toList();
    }

    /** Persist a drag-and-drop result for category folders: the given names, in this order. */
    @Transactional
    public void reorderFolders(String subject, List<String> orderedFolderNames) {
        Map<String, com.example.headlines.data.FolderPref> mine =
                folderPrefs.findByOwnerOrderByPositionAsc(subject).stream()
                        .collect(Collectors.toMap(com.example.headlines.data.FolderPref::getName,
                                Function.identity(), (a, b) -> a, LinkedHashMap::new));
        int pos = 0;
        for (String name : orderedFolderNames) {
            com.example.headlines.data.FolderPref pref = mine.get(name);
            if (pref == null) {
                folderPrefs.save(new com.example.headlines.data.FolderPref(subject, name, pos++));
            } else {
                pref.setPosition(pos++);
                folderPrefs.save(pref);
            }
        }
    }

    /** Persist a drag-and-drop result: the given subscriptions, in order, now live in {@code folder}. */
    @Transactional
    public void reorderFolder(String subject, String folder, List<Long> orderedSubscriptionIds) {
        Map<Long, Subscription> mine = subscriptions.findByOwnerOrderByFolderAscPositionAsc(subject).stream()
                .collect(Collectors.toMap(Subscription::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        int pos = 0;
        for (Long id : orderedSubscriptionIds) {
            Subscription s = mine.get(id);
            if (s == null) continue;
            s.setFolder((folder == null || folder.isBlank()) ? null : folder);
            s.setPosition(pos++);
            subscriptions.save(s);
        }
    }

    /** Subscribe the user to a feed by URL (creating the shared {@link Feed} if new), with optional
     *  HTTP credentials for an auth-gated feed. */
    @Transactional
    public void addSubscription(String subject, String url, String title, String folder,
            String username, String password) {
        Feed feed = feeds.findByUrl(url).orElseGet(() -> feeds.save(new Feed(url, title, folder)));
        Subscription existing = subscriptions.findByOwnerAndFeed(subject, feed).orElse(null);
        if (existing != null) { // already subscribed — just refresh its credentials
            applyCredentials(existing, username, password);
            subscriptions.save(existing);
            return;
        }
        int nextPos = subscriptions.findByOwnerOrderByFolderAscPositionAsc(subject).size();
        Subscription sub = new Subscription(subject, feed,
                (folder == null || folder.isBlank()) ? null : folder, nextPos);
        applyCredentials(sub, username, password);
        subscriptions.save(sub);
    }

    /** Set, update, or clear (blank username) the HTTP credentials on one of the user's feeds. */
    @Transactional
    public void setCredentials(String subject, long subscriptionId, String username, String password) {
        subscriptions.findById(subscriptionId)
                .filter(s -> s.getOwner().equals(subject))
                .ifPresent(s -> { applyCredentials(s, username, password); subscriptions.save(s); });
    }

    private static void applyCredentials(Subscription s, String username, String password) {
        boolean has = username != null && !username.isBlank();
        s.setAuthUsername(has ? username.trim() : null);
        s.setAuthPassword(has ? password : null);
    }

    /** Export the user's subscriptions as an OPML document (folders + feeds), like RSSOwl's export. */
    @Transactional(readOnly = true)
    public String exportOpml(String subject) {
        return Opml.write(feedRefs(subject));
    }

    /** Import OPML-declared feeds as new subscriptions for the user (folders preserved). Existing
     *  subscriptions are skipped. Returns the URLs of feeds newly added, so the caller can fetch them. */
    @Transactional
    public List<String> importSubscriptions(String subject, List<DefaultFeeds.Source> sources) {
        int pos = subscriptions.findByOwnerOrderByFolderAscPositionAsc(subject).size();
        List<String> added = new ArrayList<>();
        for (DefaultFeeds.Source s : sources) {
            Feed feed = feeds.findByUrl(s.url()).orElseGet(() -> feeds.save(new Feed(s.url(), s.title(), s.category())));
            if (subscriptions.findByOwnerAndFeed(subject, feed).isPresent()) continue; // already subscribed
            String folder = "Uncategorized".equals(s.category()) ? null : s.category();
            subscriptions.save(new Subscription(subject, feed, folder, pos++));
            added.add(s.url());
        }
        return added;
    }

    @Transactional
    public void removeSubscription(String subject, long subscriptionId) {
        subscriptions.findById(subscriptionId)
                .filter(s -> s.getOwner().equals(subject))
                .ifPresent(subscriptions::delete);
    }

    // --- headlines-grid column layout (order / width / visibility), per user ---

    /** The user's saved column layout in left-to-right order; empty if they never customised it. */
    @Transactional(readOnly = true)
    public List<ColumnState> columnPrefs(String subject) {
        return columnPrefs.findByOwnerOrderByPositionAsc(subject).stream()
                .map(c -> new ColumnState(c.getColKey(), c.getPosition(), c.getWidth(), c.isVisible()))
                .toList();
    }

    /** Persist the full column layout snapshot (order, width, visibility) for this user. The given
     *  list is the current left-to-right column order; each entry's {@code position} is reassigned
     *  from its index so the stored order matches exactly. */
    @Transactional
    public void saveColumnLayout(String subject, List<ColumnState> columns) {
        Map<String, com.example.headlines.data.ColumnPref> mine =
                columnPrefs.findByOwnerOrderByPositionAsc(subject).stream()
                        .collect(Collectors.toMap(com.example.headlines.data.ColumnPref::getColKey,
                                Function.identity(), (a, b) -> a, LinkedHashMap::new));
        int pos = 0;
        for (ColumnState cs : columns) {
            com.example.headlines.data.ColumnPref pref = mine.get(cs.key());
            if (pref == null) {
                columnPrefs.save(new com.example.headlines.data.ColumnPref(
                        subject, cs.key(), pos++, cs.width(), cs.visible()));
            } else {
                pref.setPosition(pos++);
                pref.setWidth(cs.width());
                pref.setVisible(cs.visible());
                columnPrefs.save(pref);
            }
        }
    }
}
