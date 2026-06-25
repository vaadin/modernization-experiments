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

    private final FeedRepository feeds;
    private final ArticleRepository articles;
    private final SubscriptionRepository subscriptions;
    private final ArticleStateRepository states;

    public UserNewsService(FeedRepository feeds, ArticleRepository articles,
            SubscriptionRepository subscriptions, ArticleStateRepository states) {
        this.feeds = feeds;
        this.articles = articles;
        this.subscriptions = subscriptions;
        this.states = states;
    }

    /** First-login bootstrap: give a brand-new user the default subscription set (from feeds.opml). */
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
                out.add(new NewsItem(a.getId(), a.getTitle(), a.getAuthor(), folder, sub.displayTitle(),
                        a.getPublishedDate(), read ? State.READ : State.UNREAD, sticky, label,
                        a.getLink(), a.isAttachments()));
            }
        }
        return out;
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

    @Transactional
    public void removeSubscription(String subject, long subscriptionId) {
        subscriptions.findById(subscriptionId)
                .filter(s -> s.getOwner().equals(subject))
                .ifPresent(subscriptions::delete);
    }
}
