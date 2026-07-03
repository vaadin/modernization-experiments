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

import com.example.headlines.Grouping.Bucket;
import com.example.headlines.Grouping.GroupBy;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.avatar.AvatarVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.example.headlines.service.AuthenticationRequiredException;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider.HierarchyFormat;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.signals.Signal;
import com.vaadin.flow.signals.local.ValueSignal;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.example.headlines.service.FeedFetchService;
import com.example.headlines.service.UserNewsService;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * POC matching RSSOwl(nix)'s three-pane structure: left = a feeds-navigation tree
 * (Category → Feed, with counts), top-right = the headlines {@link TreeGrid} (sortable, groupable),
 * bottom-right = the article reader. Headlines are the current Keycloak user's own subscriptions and
 * read/sticky state ({@link UserNewsService}); selection→reader is wired with Vaadin Signals.
 */
@Route("")
@PageTitle("Headlines — SWT→Vaadin POC")
@PermitAll // any authenticated Keycloak user may open the app
public class HeadlinesView extends Div {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter FULL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** RSSOwl-style short date: time-of-day for today, "d MMM" this year, else "d MMM yyyy". */
    private static String shortDate(java.time.LocalDateTime d) {
        if (d == null) return "";
        java.time.LocalDate day = d.toLocalDate(), today = java.time.LocalDate.now();
        if (day.equals(today)) return TIME_FMT.format(d);
        return (day.getYear() == today.getYear() ? DAY_FMT : FULL_FMT).format(d);
    }

    /** RSSOwl's default saved-search smart folders: {display name, predicate key}. */
    private static final String[][] SAVED = {
            {"Unread News", "unread"}, {"Today's News", "today"},
            {"News with Attachments", "attachments"}, {"Sticky News", "sticky"},
            {"Labeled News", "labeled"}};

    private final List<NewsItem.LabelRef> userLabels = new ArrayList<>(); // this user's managed labels
    private GridMenuItem<Row> labelRoot; // the context-menu "Label" submenu, repopulated on label CRUD
    private GridMenuItem<Row> binRoot;   // the context-menu "Add to bin" submenu, repopulated on bin CRUD
    private Long currentBinId;           // non-null when a news bin is the current tree selection
    // Custom desktop-style selection (we run the grid in NONE mode and track selection ourselves).
    private final java.util.LinkedHashSet<Long> selectedIds = new java.util.LinkedHashSet<>();
    private Long selectionAnchorId;      // anchor for Shift-range selection

    private List<NewsItem> allItems; // reloaded after add/unsubscribe
    private List<NewsItem> currentItems; // the feed/folder/smart-folder selection
    private String searchTerm = "";      // live headline filter (empty = no filter)
    private HeadlineFilter.Scope filterScope = HeadlineFilter.Scope.TITLE; // RSSOwl's SearchTarget, default headline
    private GroupBy currentGroupBy = GroupBy.NONE;

    private final TreeGrid<FeedNode> feedTree = new TreeGrid<>();
    private TreeData<FeedNode> feedData;
    private TreeDataProvider<FeedNode> feedDataProvider;
    private FeedNode draggedNode;
    private final TreeGrid<Row> headlines = new TreeGrid<>();
    private final ValueSignal<NewsItem> selected = new ValueSignal<NewsItem>((NewsItem) null);
    private final Map<Row.GroupRow, List<Row>> children = new HashMap<>();
    private Grid.Column<Row> dateColumn;
    private boolean adjustingDateSort; // re-entrancy guard while swapping the date null policy
    private List<Grid.Column<Row>> columnOrder; // current left-to-right order, for persistence

    private boolean unreadOnly = false;      // RSSOwl's "Unread" view mode: show only unread items

    // Keyboard navigation: the grid moves a focused cell natively; we react to cell-focus to drive the
    // reader + a delayed mark-read (RSSOwl's model — show at once, mark read after a dwell).
    private static final java.util.concurrent.ScheduledExecutorService DWELL_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "headline-dwell");
                t.setDaemon(true);
                return t;
            });
    private java.util.concurrent.ScheduledFuture<?> pendingDwell;
    private Long focusedId;               // id of the keyboard-focused headline (for the dwell + Enter)
    private int readDelayMs;              // -1 off, 0 instant, else the auto-read dwell (loaded from pref)
    private boolean pointerFocus;         // true during a mouse interaction, so cell-focus defers to the click handler

    private final UserNewsService news;
    private final FeedFetchService feedFetch;
    private final com.example.headlines.service.FeedBroadcaster broadcaster;
    private final AuthenticationContext authContext;
    private final String subject;      // Keycloak subject — the per-user key
    private final String displayName;  // for the header
    private com.vaadin.flow.shared.Registration broadcastReg; // live-refresh listener handle

    public HeadlinesView(UserNewsService news, FeedFetchService feedFetch,
            com.example.headlines.service.FeedBroadcaster broadcaster, AuthenticationContext authContext) {
        this.news = news;
        this.feedFetch = feedFetch;
        this.broadcaster = broadcaster;
        this.authContext = authContext;
        OidcUser user = authContext.getAuthenticatedUser(OidcUser.class)
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
        this.subject = user.getSubject();
        this.displayName = user.getPreferredUsername() != null ? user.getPreferredUsername() : user.getName();

        // First login for this user? Seed their default subscriptions + labels, then load their headlines.
        news.ensureSeeded(subject);
        news.ensureLabels(subject);
        userLabels.addAll(news.labels(subject));

        // Run the user's news filters over their news on open (RSSOwl applies filters as news arrives);
        // actions are additive/idempotent so this never undoes a manual change.
        news.applyFilters(subject);

        setSizeFull();
        this.allItems = news.newsItems(subject);
        this.currentItems = allItems;
        this.readDelayMs = news.readDelayMs(subject); // keyboard auto-read delay (per-user)

        // "New since last visit" notification (RSSOwl pops one when a refresh brings in new news).
        // Read last-seen before updating it; show the count once the view attaches.
        java.time.LocalDateTime lastSeen = news.lastSeen(subject);
        long newSinceLastVisit = lastSeen == null ? 0 : allItems.stream()
                .filter(n -> n.date() != null && n.date().isAfter(lastSeen)).count();
        news.markSeen(subject);
        if (newSinceLastVisit > 0) {
            addAttachListener(e -> {
                Notification n = Notification.show(newSinceLastVisit + " new article"
                        + (newSinceLastVisit == 1 ? "" : "s") + " since your last visit",
                        5000, Notification.Position.TOP_END);
                n.addThemeVariants(NotificationVariant.LUMO_PRIMARY);
            });
        }

        configureFeedTree();
        configureHeadlines();
        applyColumnPrefs(); // restore this user's saved column order/width/visibility
        applyGrouping(GroupBy.NONE);

        Div reader = buildReactiveReader();

        // top-right (headlines + a small toolbar) over bottom-right (reader)
        VerticalLayout headlinesPane = new VerticalLayout(buildToolbar(), headlines);
        headlinesPane.setSizeFull();
        headlinesPane.setPadding(false);
        headlinesPane.setSpacing(false);
        headlinesPane.setFlexGrow(1, headlines);

        SplitLayout right = new SplitLayout(headlinesPane, reader);
        right.setOrientation(SplitLayout.Orientation.VERTICAL);
        right.setSplitterPosition(60);
        right.setSizeFull();

        // left pane: an "Add feed / Import / Export" bar above the feeds tree
        Button addFeed = new Button("Add feed", VaadinIcon.PLUS.create(), e -> openAddFeedDialog());
        addFeed.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        Button importOpml = new Button("Import", VaadinIcon.UPLOAD.create(), e -> openImportOpmlDialog());
        importOpml.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        Anchor exportOpml = buildOpmlExportLink();
        HorizontalLayout feedBar = new HorizontalLayout(addFeed, importOpml, exportOpml);
        feedBar.setAlignItems(FlexComponent.Alignment.CENTER);
        feedBar.getStyle().set("padding", "0.3rem 0.5rem");
        VerticalLayout leftPane = new VerticalLayout(feedBar, feedTree);
        leftPane.setSizeFull();
        leftPane.setPadding(false);
        leftPane.setSpacing(false);
        leftPane.setFlexGrow(1, feedTree);

        // left feeds pane | right (headlines / reader)
        SplitLayout outer = new SplitLayout(leftPane, right);
        outer.setOrientation(SplitLayout.Orientation.HORIZONTAL);
        outer.setSplitterPosition(20);
        outer.setSizeFull();
        add(outer);

        // Live notifications: while this page is open, the periodic background refresh signals us via
        // the FeedBroadcaster; we marshal onto this UI thread (the app uses @Push) and show a toast if
        // new articles relevant to THIS user arrived. Register on attach, unregister on detach.
        addAttachListener(e -> {
            UI ui = e.getUI();
            broadcastReg = broadcaster.register(() -> ui.access(this::onNewArticlesArrived));
        });
        addDetachListener(e -> {
            if (broadcastReg != null) { broadcastReg.remove(); broadcastReg = null; }
            cancelDwell();
        });
    }

    /**
     * A background refresh added new public articles. Recompute how many are new <em>for this user</em>
     * (their subscribed feeds only) by diffing against what's loaded; if any, show a non-disruptive toast
     * with a "Show" action that pulls them into the tree + grid. Per-user accurate — a user not subscribed
     * to the updated feeds sees no toast.
     */
    private void onNewArticlesArrived() {
        List<NewsItem> latest = news.newsItems(subject);
        int delta = latest.size() - allItems.size();
        if (delta <= 0) return; // nothing new this user can see (or they already refreshed)

        Span msg = new Span(delta + " new article" + (delta == 1 ? "" : "s") + " arrived");
        Button show = new Button("Show", ev -> { reloadUserData(); });
        show.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        Notification n = new Notification(msg, show);
        n.setPosition(Notification.Position.TOP_END);
        n.setDuration(8000);
        n.addThemeVariants(NotificationVariant.LUMO_PRIMARY);
        show.addClickListener(ev -> n.close());
        n.open();
    }

    // --- left pane: feeds navigation tree (RSSOwl's BookMarkExplorer) ---

    private void configureFeedTree() {
        feedTree.setSizeFull();
        feedTree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);

        // Build the tree from the user's persisted subscriptions (folder + drag order).
        buildFeedTreeData();

        // FLATTENED keeps scroll/expansion stable across refreshAll(). Crucially, identify nodes by a
        // STABLE key (path / id) rather than the record's value-equality: a record carries its unread
        // count, so without this a changing count makes a folder "a different item" and the TreeGrid
        // silently drops its expansion — the folder collapses on every read. Stable ids let refreshAll()
        // update counts in place while keeping folders open and the selection intact.
        feedDataProvider = new TreeDataProvider<FeedNode>(feedData, HierarchyFormat.FLATTENED) {
            @Override public Object getId(FeedNode item) { return nodeId(item); }
        };
        feedTree.addHierarchyColumn(FeedNode::label).setHeader("Feeds");
        feedTree.setDataProvider(feedDataProvider);
        // Bold feeds/folders with unread, normal (greyed) when all read — RSSOwl-style. Smart folders,
        // saved searches and bins stay italic.
        feedTree.setPartNameGenerator(n -> {
            if (n instanceof FeedNode.Category c) return c.count() > 0 ? "feed-category" : "feed-category-read";
            if (n instanceof FeedNode.Feed f) return f.count() > 0 ? "feed-unread" : null;
            // Smart folders / saved searches / bins: italic to set them apart, but still BOLD when they
            // hold unread news (RSSOwl bolds anything with unread), normal+greyed once all read.
            if (n instanceof FeedNode.Saved s) return s.count() > 0 ? "feed-saved" : "feed-saved-read";
            if (n instanceof FeedNode.SavedSearch ss) return ss.count() > 0 ? "feed-saved" : "feed-saved-read";
            if (n instanceof FeedNode.Bin b) return b.count() > 0 ? "feed-saved" : "feed-saved-read";
            return null;
        });
        enableFeedDragAndDrop();

        feedTree.addSelectionListener(e -> {
            FeedNode sel = e.getFirstSelectedItem().orElse(null);
            currentBinId = (sel instanceof FeedNode.Bin b) ? b.id() : null; // track for "remove from bin"
            if (sel instanceof FeedNode.Category c) {
                // A folder shows its own feeds plus everything in its sub-folders (path prefix match).
                String p = c.path();
                currentItems = allItems.stream().filter(n -> n.category() != null
                        && (n.category().equals(p) || n.category().startsWith(p + "/"))).toList();
            } else if (sel instanceof FeedNode.Feed f) {
                currentItems = allItems.stream().filter(n -> f.name().equals(n.feed())).toList();
            } else if (sel instanceof FeedNode.Saved sv) {
                currentItems = allItems.stream().filter(savedPredicate(sv.key())).toList();
            } else if (sel instanceof FeedNode.SavedSearch ss) {
                // A saved search re-runs its Lucene query over the whole archive (results, not a filter).
                currentItems = news.search(subject, ss.query());
            } else if (sel instanceof FeedNode.Bin b) {
                currentItems = news.binItems(subject, b.id());
            } else {
                currentItems = allItems;
            }
            applyGrouping(currentGroupBy);
        });

        // Make a folder's NAME select it (show its cumulative article list), not just expand.
        // The name is rendered inside the vaadin-grid-tree-toggle, which consumes the click for
        // expand/collapse and stops it propagating — so addItemClickListener does NOT fire on a name
        // click (only on the empty row area). We therefore also select on expand/collapse: a client-driven
        // folder-name click toggles the folder AND selects it (→ cumulative list). item-click still covers
        // clicks on the empty row area and on leaf rows.
        feedTree.addItemClickListener(e -> {
            if (e.getItem() != null) feedTree.select(e.getItem());
        });
        feedTree.addExpandListener(e -> {
            if (e.isFromClient()) e.getItems().forEach(feedTree::select);
        });
        feedTree.addCollapseListener(e -> {
            if (e.isFromClient()) e.getItems().forEach(feedTree::select);
        });

        // Right-click a channel: set credentials / unsubscribe; right-click a saved search: delete it.
        GridContextMenu<FeedNode> feedMenu = feedTree.addContextMenu();
        GridMenuItem<FeedNode> credItem = feedMenu.addItem("Set credentials…", e -> e.getItem()
                .filter(n -> n instanceof FeedNode.Feed)
                .ifPresent(n -> openCredentialsDialog((FeedNode.Feed) n)));
        GridMenuItem<FeedNode> unsubItem = feedMenu.addItem("Unsubscribe", e -> e.getItem()
                .filter(n -> n instanceof FeedNode.Feed).ifPresent(this::unsubscribe));
        GridMenuItem<FeedNode> delSearchItem = feedMenu.addItem("Delete saved search", e -> e.getItem()
                .filter(n -> n instanceof FeedNode.SavedSearch).ifPresent(n -> {
                    news.deleteSavedSearch(subject, ((FeedNode.SavedSearch) n).id());
                    reloadUserData();
                    Notification.show("Saved search deleted");
                }));
        GridMenuItem<FeedNode> delBinItem = feedMenu.addItem("Delete bin", e -> e.getItem()
                .filter(n -> n instanceof FeedNode.Bin).ifPresent(n -> {
                    news.deleteBin(subject, ((FeedNode.Bin) n).id());
                    reloadUserData();
                    Notification.show("Bin deleted");
                }));
        // Mark all read on a feed or folder (RSSOwl staple), keyed off the node's scope.
        GridMenuItem<FeedNode> markAllItem = feedMenu.addItem("Mark all read",
                e -> e.getItem().ifPresent(this::markAllRead));
        feedMenu.setDynamicContentHandler(node -> {
            boolean feed = node instanceof FeedNode.Feed;
            boolean savedSearch = node instanceof FeedNode.SavedSearch;
            boolean bin = node instanceof FeedNode.Bin;
            boolean folder = node instanceof FeedNode.Category;
            credItem.setVisible(feed);
            unsubItem.setVisible(feed);
            delSearchItem.setVisible(savedSearch);
            delBinItem.setVisible(bin);
            markAllItem.setVisible(feed || folder); // feeds and folders can be bulk-read
            return feed || savedSearch || bin || folder;
        });
    }

    /** Mark every (currently-unread) item in the given feed or folder read — RSSOwl's "Mark all read". */
    private void markAllRead(FeedNode node) {
        java.util.function.Predicate<NewsItem> inScope;
        String label;
        if (node instanceof FeedNode.Feed f) {
            inScope = n -> f.name().equals(n.feed());
            label = f.name();
        } else if (node instanceof FeedNode.Category c) {
            String p = c.path();
            inScope = n -> n.category() != null && (n.category().equals(p) || n.category().startsWith(p + "/"));
            label = c.name();
        } else {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (NewsItem n : allItems) {
            if (n.unread() && inScope.test(n)) { n.setRead(true); ids.add(n.id()); }
        }
        if (ids.isEmpty()) { Notification.show("Nothing unread in " + label); return; }
        news.setReadBulk(subject, ids, true);
        applyGrouping(currentGroupBy); // re-render the grid (read styling)
        refreshTreeCounts();
        Notification.show("Marked " + ids.size() + " read in " + label);
    }

    /** A download link that serves the user's current subscriptions as an OPML file (RSSOwl-style export). */
    private Anchor buildOpmlExportLink() {
        Anchor link = new Anchor(DownloadHandler.fromInputStream(event -> {
            byte[] opml = news.exportOpml(subject).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new DownloadResponse(new java.io.ByteArrayInputStream(opml),
                    "subscriptions.opml", "text/x-opml", opml.length);
        }), "Export");
        link.getStyle().set("font-size", "var(--lumo-font-size-s)").set("align-self", "center");
        return link;
    }

    /** Import subscriptions from an uploaded OPML file (folders + feeds), like RSSOwl's import. */
    private void openImportOpmlDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Import OPML");
        dialog.setWidth("460px");
        Paragraph hint = new Paragraph("Upload an OPML file to add its feeds to your subscriptions. "
                + "Feeds you're already subscribed to are skipped.");
        hint.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");

        Upload upload = new Upload(UploadHandler.inMemory((metadata, data) -> {
            // Built-in handler invokes this on the UI thread, so UI updates here are safe.
            try {
                var sources = com.example.headlines.service.DefaultFeeds.parse(new java.io.ByteArrayInputStream(data));
                List<String> newUrls = news.importSubscriptions(subject, sources);
                reloadUserData();
                int skipped = sources.size() - newUrls.size();
                Notification.show("Imported " + newUrls.size() + " new feed(s)"
                        + (skipped > 0 ? " (" + skipped + " already subscribed)" : ""));
                dialog.close();
                fetchInBackground(newUrls); // pull the new feeds' articles, then refresh the tree
            } catch (Exception ex) {
                Notification.show("Import failed: " + ex.getMessage())
                        .addThemeVariants(NotificationVariant.ERROR);
            }
        }));
        upload.setAcceptedFileTypes("text/x-opml", ".opml", ".xml", "text/xml", "application/xml");
        upload.setMaxFiles(1);

        dialog.add(new VerticalLayout(hint, upload));
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    /** Fetch the given feeds' articles off the UI thread, then refresh the tree via push. */
    private void fetchInBackground(List<String> urls) {
        if (urls.isEmpty()) return;
        UI ui = UI.getCurrent();
        Thread t = new Thread(() -> {
            for (String url : urls) feedFetch.refreshPublic(url);
            ui.access(this::reloadUserData);
        }, "opml-import-fetch");
        t.setDaemon(true);
        t.start();
    }

    private void openAddFeedDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Add feed");
        dialog.setWidth("460px"); // wide enough to show the example URL in full
        TextField url = new TextField("Feed URL");
        url.setWidthFull();
        url.setPlaceholder("https://example.com/rss");
        TextField title = new TextField("Title (optional)");
        title.setWidthFull();
        TextField folder = new TextField("Folder (optional)");
        folder.setWidthFull();
        // Optional HTTP credentials (RSSOwl's per-feed auth), tucked behind a disclosure so the common
        // "just paste a public feed URL" case stays clean.
        TextField user = new TextField("Username");
        user.setWidthFull();
        PasswordField pass = new PasswordField("Password");
        pass.setWidthFull();
        Details creds = new Details("This feed requires a login", new VerticalLayout(user, pass));
        creds.getElement().getStyle().set("width", "100%");
        VerticalLayout form = new VerticalLayout(url, title, folder, creds);
        form.setPadding(false);
        form.setSpacing(true);
        dialog.add(form);

        Button add = new Button("Add", e -> {
            String u = url.getValue() == null ? "" : url.getValue().trim();
            if (u.isEmpty()) {
                url.setInvalid(true);
                url.setErrorMessage("A feed URL is required");
                return;
            }
            String t = title.getValue().isBlank() ? u : title.getValue().trim();
            news.addSubscription(subject, u, t, folder.getValue().trim(), user.getValue(), pass.getValue());
            try {
                if (user.getValue().isBlank()) {
                    feedFetch.refreshPublic(u);                                  // anonymous, shared
                } else {
                    feedFetch.refreshForUser(u, subject, user.getValue().trim(), pass.getValue()); // private to this user
                }
            } catch (AuthenticationRequiredException auth) {
                pass.setInvalid(true);
                pass.setErrorMessage("Authentication failed (401) — check username/password");
                reloadUserData(); // the subscription is saved; let them fix the password and retry
                return;
            }
            reloadUserData();
            dialog.close();
            Notification.show("Subscribed to " + t);
        });
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), add);
        dialog.open();
    }

    /** Save the current query as a named saved search (shown as a smart folder in the tree). */
    private void openSaveSearchDialog(String query) {
        if (query == null || query.isBlank()) return;
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Save search");
        dialog.setWidth("460px");
        TextField name = new TextField("Name");
        name.setWidthFull();
        name.setValue(query); // sensible default: the query text
        Span q = new Span("Query: " + query);
        q.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)").set("font-size", "var(--lumo-font-size-s)");
        dialog.add(new VerticalLayout(name, q));
        Button save = new Button("Save", e -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true); name.setErrorMessage("A name is required"); return;
            }
            news.createSavedSearch(subject, name.getValue().trim(), query);
            reloadUserData();
            dialog.close();
            Notification.show("Saved search created");
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), save);
        dialog.open();
    }

    /** Login dialog for a feed's HTTP credentials (RSSOwl: "Feed requires authentication"). */
    private void openCredentialsDialog(FeedNode.Feed feed) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Feed requires authentication");
        dialog.setWidth("460px");
        Span msg = new Span("Enter the username and password for \"" + feed.name() + "\".");
        TextField user = new TextField("Username");
        user.setWidthFull();
        if (feed.authUsername() != null) user.setValue(feed.authUsername());
        PasswordField pass = new PasswordField("Password");
        pass.setWidthFull();
        VerticalLayout form = new VerticalLayout(msg, user, pass);
        form.setPadding(false);
        dialog.add(form);

        Button save = new Button("Save", e -> {
            news.setCredentials(subject, feed.subscriptionId(), user.getValue(), pass.getValue());
            try {
                if (user.getValue().isBlank()) {
                    feedFetch.refreshPublic(feed.url());                         // cleared creds -> anonymous
                } else {
                    feedFetch.refreshForUser(feed.url(), subject, user.getValue().trim(), pass.getValue());
                }
            } catch (AuthenticationRequiredException auth) {
                pass.setInvalid(true);
                pass.setErrorMessage("Authentication failed (401) — check username/password");
                return;
            }
            reloadUserData();
            dialog.close();
            Notification.show(user.getValue().isBlank()
                    ? "Cleared credentials for " + feed.name()
                    : "Credentials saved for " + feed.name());
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), save);
        dialog.open();
    }

    private void unsubscribe(FeedNode node) {
        if (node instanceof FeedNode.Feed f) {
            news.removeSubscription(subject, f.subscriptionId());
            reloadUserData();
            Notification.show("Unsubscribed from " + f.name());
        }
    }

    /** Re-query the current user's data and rebuild the tree + grid (after add/unsubscribe). */
    private void reloadUserData() {
        this.allItems = news.newsItems(subject);
        buildFeedTreeData();           // clears+repopulates the SAME feedData (keeps provider + expansion)
        feedDataProvider.refreshAll();
        this.currentItems = allItems;
        applyGrouping(currentGroupBy);
    }

    /** Rebuild only the feeds-tree counts/bolding from the in-memory items (no DB reload, keeps the
     *  current selection/grid). Called after read/sticky/label changes so unread counts stay live. */
    private void refreshTreeCounts() {
        buildFeedTreeData();           // clears+repopulates the SAME feedData with new unread counts
        feedDataProvider.refreshAll(); // re-render in place: stable ids keep folders expanded + selection
    }

    /**
     * A node's STABLE identity for the tree data provider — independent of the unread {@code count}
     * carried in the record (which changes on every read). Without this the {@link TreeGrid} treats a
     * recounted folder as a brand-new item and drops its expansion, collapsing the tree. See the
     * provider setup in the constructor.
     */
    private static Object nodeId(FeedNode n) {
        return switch (n) {
            case FeedNode.Category c    -> "cat:" + c.path();
            case FeedNode.Feed f        -> "feed:" + f.subscriptionId();
            case FeedNode.Saved s       -> "saved:" + s.key();
            case FeedNode.SavedSearch ss -> "search:" + ss.id();
            case FeedNode.Bin b         -> "bin:" + b.id();
        };
    }

    /**
     * (Re)builds the tree's {@link TreeData} from the current user's persisted {@code Subscription}s.
     * Folders nest to any depth from the subscription's folder <em>path</em> ({@code "Computers/Windows"}),
     * so the tree mirrors the original's nested defaults. Within each level, sub-folders and feeds are
     * interleaved in their original OPML order (the subscription's saved position); a user's folder
     * drag-reorder overrides that at the top level. Loose channels (no folder) sit at the root, then the
     * saved-search smart folders. Article counts (folders aggregate their descendants') come from the
     * loaded headlines.
     */
    private void buildFeedTreeData() {
        // Reuse the SAME TreeData instance so the existing provider (and the TreeGrid's expansion state)
        // survives a rebuild; only its contents are swapped out.
        if (feedData == null) feedData = new TreeData<>();
        else feedData.clear();
        // UNREAD counts per feed (RSSOwl shows unread, not total). Recomputed from the in-memory items,
        // so refreshTreeCounts() reflects read/unread changes without a DB round-trip.
        Map<String, Long> unreadByFeed = allItems.stream().filter(NewsItem::unread)
                .collect(Collectors.groupingBy(NewsItem::feed, Collectors.counting()));

        // Feeds in OPML order; each folder records its descendants' unread count and earliest position.
        List<UserNewsService.FeedRef> ordered = new ArrayList<>(news.feedRefs(subject));
        ordered.sort(Comparator.comparingInt(UserNewsService.FeedRef::position));

        Map<String, List<UserNewsService.FeedRef>> directFeeds = new LinkedHashMap<>(); // path ("" = root) -> feeds
        Map<String, Integer> countByPath = new HashMap<>(); // folder path -> unread total (incl. sub-folders)
        Map<String, Integer> posByPath = new HashMap<>();    // folder path -> min OPML position (ordering key)
        Set<String> catPaths = new LinkedHashSet<>();
        for (UserNewsService.FeedRef r : ordered) {
            String folder = folderOf(r.folder()); // "" for a loose top-level channel
            int cnt = unreadByFeed.getOrDefault(r.title(), 0L).intValue();
            directFeeds.computeIfAbsent(folder, k -> new ArrayList<>()).add(r);
            String acc = "";
            for (String seg : folder.isEmpty() ? new String[0] : folder.split("/")) {
                acc = acc.isEmpty() ? seg : acc + "/" + seg;
                catPaths.add(acc);
                countByPath.merge(acc, cnt, Integer::sum);
                posByPath.merge(acc, r.position(), Math::min);
            }
        }

        List<String> savedOrder = news.folderOrder(subject); // top-level folder drag order, if any
        addFeedTreeLevel("", null, directFeeds, catPaths, countByPath, posByPath, unreadByFeed, savedOrder);

        for (String[] s : SAVED) {
            int c = (int) allItems.stream().filter(savedPredicate(s[1])).count();
            feedData.addItem(null, new FeedNode.Saved(s[0], s[1], c));
        }
        // The user's own saved searches (persisted Lucene queries), below the fixed smart folders.
        for (UserNewsService.SavedSearchRef ss : news.savedSearches(subject)) {
            int c = ss.query().isBlank() ? 0 : news.search(subject, ss.query()).size();
            feedData.addItem(null, new FeedNode.SavedSearch(ss.id(), ss.name(), ss.query(), c));
        }
        // The user's news bins (containers of explicitly-added articles), at the bottom.
        for (UserNewsService.BinRef b : news.bins(subject)) {
            feedData.addItem(null, new FeedNode.Bin(b.id(), b.name(), b.count()));
        }
    }

    /** Adds the sub-folders and feeds directly under {@code parentPath} (root when empty), interleaved
     *  in OPML order — recursing into each sub-folder. */
    private void addFeedTreeLevel(String parentPath, FeedNode.Category parentNode,
            Map<String, List<UserNewsService.FeedRef>> directFeeds, Set<String> catPaths,
            Map<String, Integer> countByPath, Map<String, Integer> posByPath,
            Map<String, Long> unreadByFeed, List<String> savedOrder) {
        // Combined, position-ordered list of this level's children: sub-folder paths and feed refs.
        List<Object> children = new ArrayList<>();
        catPaths.stream().filter(p -> isDirectChild(parentPath, p)).forEach(children::add);
        children.addAll(directFeeds.getOrDefault(parentPath, List.of()));
        children.sort(Comparator.comparingInt(c -> levelOrderKey(c, parentPath, posByPath, savedOrder)));

        for (Object child : children) {
            if (child instanceof String cp) {
                FeedNode.Category cat = new FeedNode.Category(cp, lastSegment(cp), countByPath.getOrDefault(cp, 0));
                feedData.addItem(parentNode, cat);
                addFeedTreeLevel(cp, cat, directFeeds, catPaths, countByPath, posByPath, unreadByFeed, savedOrder);
            } else {
                UserNewsService.FeedRef r = (UserNewsService.FeedRef) child;
                feedData.addItem(parentNode, new FeedNode.Feed(r.title(),
                        parentPath.isEmpty() ? "Uncategorized" : parentPath,
                        unreadByFeed.getOrDefault(r.title(), 0L).intValue(), r.subscriptionId(),
                        r.url(), r.authUsername()));
            }
        }
    }

    /** Ordering key for one child at a level: its OPML position. Top-level folders the user dragged are
     *  pulled to the front in saved order (negative keys), so folder reordering still wins there. */
    private static int levelOrderKey(Object child, String parentPath, Map<String, Integer> posByPath,
            List<String> savedOrder) {
        if (parentPath.isEmpty() && child instanceof String cp) {
            int saved = savedOrder.indexOf(cp);
            if (saved >= 0) return Integer.MIN_VALUE + saved; // dragged top-level folders, in saved order
        }
        return child instanceof String cp ? posByPath.getOrDefault(cp, Integer.MAX_VALUE)
                : ((UserNewsService.FeedRef) child).position();
    }

    /** True when {@code path} is an immediate child folder of {@code parentPath} ({@code ""} = root). */
    private static boolean isDirectChild(String parentPath, String path) {
        if (parentPath.isEmpty()) return !path.contains("/");
        return path.startsWith(parentPath + "/") && path.indexOf('/', parentPath.length() + 1) < 0;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Normalise a stored folder to a path: null/blank/"Uncategorized" become "" (a root-level channel). */
    private static String folderOf(String folder) {
        return (folder == null || folder.isBlank() || "Uncategorized".equals(folder)) ? "" : folder;
    }

    /**
     * Mouse drag-and-drop reordering of the feeds tree, like RSSOwl's BookMarkExplorer. Channels
     * (Feed nodes) and category folders are draggable; saved-search folders stay put. Dropping a
     * channel reorders it among its siblings, drops it <em>into</em> a folder (drop on top of one), or
     * pops it back out to the top level; dropping a folder reorders it among the other folders. All
     * moves are persisted per user.
     */
    private void enableFeedDragAndDrop() {
        feedTree.setRowsDraggable(true);
        feedTree.setDragFilter(n -> n instanceof FeedNode.Feed || n instanceof FeedNode.Category);
        // Set the drop mode once, up front, rather than inside dragStart: a per-drag setDropMode
        // only reaches the client after a server round-trip, which can make the first drop after
        // grab flaky. It has no visible effect except while a drag is actually in progress.
        feedTree.setDropMode(GridDropMode.ON_TOP_OR_BETWEEN);

        feedTree.addDragStartListener(e -> draggedNode = e.getDraggedItems().get(0));
        feedTree.addDragEndListener(e -> draggedNode = null);
        feedTree.addDropListener(e -> {
            FeedNode target = e.getDropTargetItem().orElse(null);
            if (draggedNode == null || target == null || target.equals(draggedNode)) {
                return;
            }
            GridDropLocation loc = e.getDropLocation();
            if (draggedNode instanceof FeedNode.Feed df) {
                moveFeed(df, target, loc);
                persistFeedOrder(df); // save the new folder + order for this user
            } else if (draggedNode instanceof FeedNode.Category dc) {
                if (!(target instanceof FeedNode.Category)) {
                    return; // folders reorder only among other folders
                }
                placeRelative(dc, null, target, loc == GridDropLocation.ABOVE ? loc : GridDropLocation.BELOW);
                persistFolderOrder();
            }
            feedDataProvider.refreshAll();
            feedTree.select(draggedNode); // keep the moved row selected, like RSSOwl
        });
    }

    /** Persist the destination folder's new order (and the moved feed's folder) for this user. */
    private void persistFeedOrder(FeedNode.Feed moved) {
        FeedNode parent = feedData.getParent(moved); // null => top-level channel
        String folder = (parent instanceof FeedNode.Category c) ? c.path() : null;
        List<Long> orderedIds = feedData.getChildren(parent).stream()
                .filter(n -> n instanceof FeedNode.Feed)
                .map(n -> ((FeedNode.Feed) n).subscriptionId())
                .toList();
        news.reorderFolder(subject, folder, orderedIds);
    }

    /** Persist the new category-folder order for this user (root-level Category nodes, in order). */
    private void persistFolderOrder() {
        List<String> orderedPaths = feedData.getChildren(null).stream()
                .filter(n -> n instanceof FeedNode.Category)
                .map(n -> ((FeedNode.Category) n).path())
                .toList();
        news.reorderFolders(subject, orderedPaths);
    }

    /** Re-parents/reorders {@code dragged} relative to {@code target} for the given drop location. */
    private void moveFeed(FeedNode.Feed dragged, FeedNode target, GridDropLocation loc) {
        if (target instanceof FeedNode.Category cat) {
            if (loc == GridDropLocation.ON_TOP) {
                feedData.setParent(dragged, cat);  // drop INTO the folder
            } else {
                feedData.setParent(dragged, null); // reorder at top level, around the folder
                placeRelative(dragged, null, cat, loc);
            }
        } else if (target instanceof FeedNode.Feed tf) {
            FeedNode parent = feedData.getParent(tf); // null => top level
            feedData.setParent(dragged, parent);
            // A feed can't nest under another feed, so treat ON_TOP of a feed as "after".
            placeRelative(dragged, parent, tf, loc == GridDropLocation.ABOVE ? loc : GridDropLocation.BELOW);
        } else { // a Saved smart folder — keep channels above it; reorder at top level
            feedData.setParent(dragged, null);
            placeRelative(dragged, null, target, loc == GridDropLocation.ABOVE ? loc : GridDropLocation.BELOW);
        }
    }

    /** Positions {@code dragged} immediately above/below {@code target} among {@code parent}'s children. */
    private void placeRelative(FeedNode dragged, FeedNode parent, FeedNode target, GridDropLocation loc) {
        if (loc != GridDropLocation.ABOVE) {
            feedData.moveAfterSibling(dragged, target);
        } else {
            List<FeedNode> sibs = new ArrayList<>(feedData.getChildren(parent));
            sibs.remove(dragged);
            int idx = sibs.indexOf(target);
            feedData.moveAfterSibling(dragged, idx <= 0 ? null : sibs.get(idx - 1)); // null => first
        }
    }

    private Component buildToolbar() {
        Select<GroupBy> groupBy = new Select<>();
        groupBy.setLabel("Group by");
        groupBy.setItems(GroupBy.values());
        groupBy.setItemLabelGenerator(GroupBy::label);
        groupBy.setValue(GroupBy.NONE);
        groupBy.addValueChangeListener(e -> {
            currentGroupBy = e.getValue();
            applyGrouping(currentGroupBy);
        });

        // RSSOwl's Filter Bar: a LIVE, LOCAL filter of the currently displayed headlines, scoped like
        // RSSOwl's SearchTarget and defaulting to the title. (Global full-text stays available via saved
        // searches; the News Filters dialog is the separate conditions→actions rules engine.)
        TextField search = new TextField();
        search.setPlaceholder(filterScope.placeholder()); // grey hint tracks the scope (Title / Entire article)
        search.setClearButtonVisible(true);
        search.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        search.setPrefixComponent(VaadinIcon.FILTER.create());

        // Scope selector — Title (default) or Entire article. "Entire article" also includes the title.
        Select<HeadlineFilter.Scope> scope = new Select<>();
        scope.setItems(HeadlineFilter.Scope.values());
        scope.setItemLabelGenerator(HeadlineFilter.Scope::label);
        scope.setValue(filterScope);
        scope.setWidth("9em");
        scope.getElement().setAttribute("title", "What the filter text matches");
        scope.addValueChangeListener(e -> {
            filterScope = e.getValue();
            search.setPlaceholder(filterScope.placeholder()); // update the grey hint to match the scope
            if (!searchTerm.isBlank()) applyGrouping(currentGroupBy);
        });

        // Save the current filter as a global saved search (RSSOwl: persist a search as a smart folder).
        Button saveSearch = new Button(VaadinIcon.BOOKMARK.create(), e -> openSaveSearchDialog(searchTerm));
        saveSearch.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        saveSearch.getElement().setAttribute("title", "Save as a global saved search");
        saveSearch.setEnabled(false);

        search.addValueChangeListener(e -> {
            searchTerm = e.getValue() == null ? "" : e.getValue().trim();
            saveSearch.setEnabled(!searchTerm.isBlank());
            applyGrouping(currentGroupBy);
        });

        // Column visibility menu (RSSOwl lets you show/hide news columns). Title stays mandatory.
        MenuBar columnsMenu = new MenuBar();
        columnsMenu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_SMALL);
        SubMenu cols = columnsMenu.addItem("Columns").getSubMenu();
        addColumnToggle(cols, "date", "Date");
        addColumnToggle(cols, "author", "Author");
        addColumnToggle(cols, "category", "Category");

        // News filters (RSSOwl's rules engine: match conditions → actions).
        Button filters = new Button("Filters", VaadinIcon.FILTER.create(),
                e -> new FiltersDialog(news, subject, this::reloadUserData).open());
        filters.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        // "Unread only" view mode (RSSOwl's Unread tab). It's a VIEW control, so it belongs with
        // Columns/Filters on the left — styled as a matching tertiary toggle button (icon + label, filled
        // when active), NOT an identity-style pill. As a button it inherits the toolbar's END baseline,
        // which also fixes the "floating too high" the centered checkbox had.
        // (There used to be a "Mark read after viewing" dwell toggle here; it's gone — opening an article
        // now marks it read immediately via markReadOnOpen(), so the delayed variant was redundant.)
        Button unreadToggle = new Button("Unread only", VaadinIcon.ENVELOPE.create());
        unreadToggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        styleViewToggle(unreadToggle, unreadOnly);
        unreadToggle.addClickListener(e -> {
            unreadOnly = !unreadOnly;
            styleViewToggle(unreadToggle, unreadOnly);
            applyGrouping(currentGroupBy);
        });

        // Auto-read delay for keyboard navigation (RSSOwl's MARK_READ_STATE + MARK_READ_IN_MILLIS, but a
        // single global per-user setting — deliberately not per-feed). Persisted per user.
        Select<Integer> autoRead = new Select<>();
        autoRead.setLabel("Auto-read");
        autoRead.setItems(-1, 0, 500, 1000, 2000, 5000);
        autoRead.setItemLabelGenerator(ms -> switch (ms) {
            case -1 -> "Off";
            case 0 -> "Instant";
            case 500 -> "0.5s";
            case 1000 -> "1s";
            case 2000 -> "2s";
            case 5000 -> "5s";
            default -> ms + "ms";
        });
        autoRead.setValue(readDelayMs);
        autoRead.setWidth("6.5em");
        autoRead.getElement().setAttribute("title", "When keyboard-navigating, mark the focused article read after this delay");
        autoRead.addValueChangeListener(e -> {
            readDelayMs = e.getValue();
            news.setReadDelayMs(subject, readDelayMs);
            cancelDwell();
        });

        // Signed-in identity + logout, grouped into a single pill on the right (multi-user: whose data this is).
        Avatar avatar = new Avatar(displayName);
        avatar.addThemeVariants(AvatarVariant.LUMO_XSMALL);
        // Theme tokens per the Vaadin MCP (Aura theme): base --vaadin-* properties work in every theme
        // and are light/dark-adaptive; --aura-* is used only where base styles define no token
        // (font size/weight), with a literal fallback.
        Span who = new Span("Signed in as ");
        who.getStyle().set("color", "var(--vaadin-text-color-secondary)")
                .set("font-size", "var(--aura-font-size-s, 0.8125rem)");
        Span whoName = new Span(displayName);
        whoName.getStyle().set("font-weight", "var(--aura-font-weight-semibold, 600)")
                .set("font-size", "var(--aura-font-size-s, 0.8125rem)");
        Span identity = new Span(who, whoName); // "Signed in as <name>" as one inline label
        Button logout = new Button("Log out", VaadinIcon.SIGN_OUT.create(), e -> authContext.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        HorizontalLayout userGroup = new HorizontalLayout(avatar, identity, logout);
        userGroup.setSpacing(false);
        userGroup.setAlignItems(FlexComponent.Alignment.CENTER);
        userGroup.getStyle()
                .set("align-self", "center")
                .set("gap", "var(--vaadin-gap-s)")
                .set("padding", "var(--vaadin-padding-xs) var(--vaadin-padding-s)")
                .set("background", "var(--vaadin-background-container)") // container surface; adapts to light/dark
                .set("border", "1px solid var(--vaadin-border-color-secondary)")
                .set("border-radius", "999px"); // full pill — base radius tokens are for rects, not pills

        Div spacer = new Div(); // flexible gap that pins the user pill to the far right

        HorizontalLayout bar = new HorizontalLayout(groupBy, search, scope, saveSearch, columnsMenu, filters,
                unreadToggle, autoRead, spacer, userGroup);
        bar.setAlignItems(FlexComponent.Alignment.END);
        bar.setWidthFull();
        bar.setFlexGrow(1, spacer); // spacer eats the slack, keeping the pill grouped on the right
        bar.getStyle().set("padding", "0.4rem 1rem");
        return bar;
    }

    /** A checkable "show this column" item that toggles visibility and persists the layout. */
    private void addColumnToggle(SubMenu menu, String key, String label) {
        Grid.Column<Row> col = headlines.getColumnByKey(key);
        MenuItem item = menu.addItem(label, e -> {
            col.setVisible(e.getSource().isChecked());
            persistColumnLayout();
        });
        item.setCheckable(true);
        item.setChecked(col.isVisible()); // reflects any state already restored by applyColumnPrefs()
        item.setKeepOpen(true);           // let the user toggle several without reopening the menu
    }

    // --- top-right pane: headlines ---

    private void configureHeadlines() {
        headlines.setSizeFull();
        headlines.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        // Custom desktop-style selection (RSSOwl): plain click selects one, Cmd/Ctrl toggles, Shift
        // ranges — NO checkbox column (Vaadin's MULTI mode forces one, so we drive selection ourselves).
        headlines.setSelectionMode(TreeGrid.SelectionMode.NONE);

        // Columns match the original's News table: Title · Date · Author · Category. The read/unread
        // state is an icon inside the Title cell (as in RSSOwl), not a separate column; there are no
        // inline read/sticky toggle columns (those actions live in the context menu + reader footer).
        headlines.addComponentHierarchyColumn(this::titleComponent)
                .setHeader("Title").setKey("title").setFlexGrow(3).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::title, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        dateColumn = headlines.addColumn(row -> {
                    if (row instanceof Row.ItemRow ir && ir.news().date() != null) {
                        return shortDate(ir.news().date());
                    }
                    return "";
                })
                .setHeader("Date").setKey("date").setWidth("150px").setFlexGrow(0).setResizable(true)
                .setSortable(true);
        applyDateNullPolicy(true); // default view is date DESC — keep undated rows last, not first

        headlines.addColumn(row -> row instanceof Row.ItemRow ir ? ir.news().author() : "")
                .setHeader("Author").setKey("author").setFlexGrow(1).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::author, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        headlines.addColumn(row -> row instanceof Row.ItemRow ir ? ir.news().category() : "")
                .setHeader("Category").setKey("category").setFlexGrow(1).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::category, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        headlines.setPartNameGenerator(row -> {
            if (row instanceof Row.GroupRow) return "group";
            Row.ItemRow ir = (Row.ItemRow) row;
            StringBuilder sb = new StringBuilder();
            if (ir.news().unread()) sb.append("unread"); else sb.append("read"); // read = greyed (RSSOwl)
            if (ir.news().sticky()) sb.append(" sticky");
            if (selectedIds.contains(ir.news().id())) sb.append(" selected");
            return sb.toString();
        });

        // A mouse interaction owns selection via the click handler below; the flag lets the cell-focus
        // listener tell mouse-driven focus (skip) from keyboard-driven focus (handle). Sequence is
        // mousedown -> focus -> mouseup, so the flag is set before cell-focus fires during a click.
        headlines.getElement().addEventListener("mousedown", e -> pointerFocus = true);
        headlines.getElement().addEventListener("mouseup", e -> pointerFocus = false);

        // Desktop-style click selection: plain = select one + open; Cmd/Ctrl = toggle; Shift = range.
        headlines.addItemClickListener(e -> {
            cancelDwell(); // a click cancels any pending keyboard auto-read
            if (!(e.getItem() instanceof Row.ItemRow ir)) return;
            long id = ir.news().id();
            boolean opening = false;
            if (e.isMetaKey() || e.isCtrlKey()) {
                if (!selectedIds.remove(id)) selectedIds.add(id);
                selectionAnchorId = id;
            } else if (e.isShiftKey() && selectionAnchorId != null) {
                selectRange(selectionAnchorId, id);
                selectionAnchorId = id;
            } else {
                selectedIds.clear(); selectedIds.add(id); selectionAnchorId = id;
                opening = true;                   // a plain click OPENS the article in the reader
            }
            focusedId = id;                       // keep keyboard focus in sync with the click
            if (opening) markReadOnOpen(ir.news()); // RSSOwl: opening an article marks it read (de-bold + count--)
            selected.set(ir.news());              // reader shows the clicked item (already read → "Mark unread")
            headlines.getDataProvider().refreshAll(); // restyle selection highlight
        });
        headlines.addItemDoubleClickListener(e -> {
            if (e.getItem() instanceof Row.ItemRow ir) openLink(ir.news());
        });

        // Keyboard navigation. The grid moves a focused cell natively on Arrow/Home/End/PageUp/Down; we
        // react to it (keyboard only — mouse is handled above). RSSOwl's model: show the focused article
        // in the reader at once, and mark it read after the auto-read delay.
        headlines.addCellFocusListener(e -> {
            if (pointerFocus) return; // mouse focus — the click handler owns it
            cancelDwell();
            if (!(e.getItem().orElse(null) instanceof Row.ItemRow ir)) { focusedId = null; return; }
            NewsItem item = ir.news();
            long id = item.id();
            focusedId = id;
            selectedIds.clear(); selectedIds.add(id); selectionAnchorId = id;
            selected.set(item);                        // reader updates immediately (RSSOwl)
            headlines.getDataProvider().refreshAll();  // highlight follows the cursor
            if (readDelayMs == 0) {
                markReadOnOpen(item);                  // "Instant"
            } else if (readDelayMs > 0) {
                UI ui = UI.getCurrent();
                pendingDwell = DWELL_SCHEDULER.schedule(() -> ui.access(() -> {
                    if (focusedId != null && focusedId == id) markReadOnOpen(item);
                }), readDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } // readDelayMs < 0 == "Off": show but never auto-mark
        });

        // Enter on the focused headline: unread -> read now; read -> mark unread (a deliberate toggle).
        // Use Vaadin Shortcuts scoped to the grid (robust across focus/shadow-DOM, and only fires while
        // the headline grid has focus — so Enter in the search box or dialogs is unaffected).
        com.vaadin.flow.component.Shortcuts.addShortcutListener(this, this::onEnterKey,
                com.vaadin.flow.component.Key.ENTER).listenOn(headlines);

        // Column customisation (RSSOwl persists per-column order/width/visibility): let the user
        // drag column headers to reorder and drag header edges to resize; persist either change.
        headlines.setColumnReorderingAllowed(true);
        columnOrder = new ArrayList<>(headlines.getColumns());
        headlines.addColumnReorderListener(e -> {
            columnOrder = new ArrayList<>(e.getColumns());
            persistColumnLayout();
        });
        headlines.addColumnResizeListener(e -> persistColumnLayout());

        // Direction-aware null handling for the Date column: when the user flips to ascending/descending,
        // swap the null policy so undated rows stay at the bottom in BOTH directions (see applyDateNullPolicy).
        headlines.addSortListener(e -> {
            if (adjustingDateSort) return;
            e.getSortOrder().stream().filter(so -> so.getSorted() == dateColumn).findFirst().ifPresent(so -> {
                adjustingDateSort = true;
                try {
                    applyDateNullPolicy(so.getDirection() == SortDirection.DESCENDING);
                    headlines.getDataProvider().refreshAll(); // re-sort with the adjusted comparator
                } finally {
                    adjustingDateSort = false;
                }
            });
        });

        buildContextMenu();
    }

    /**
     * Pin undated rows to the BOTTOM regardless of sort direction. RSSOwl's {@code NewsComparator} is
     * direction-aware and does this directly; Vaadin instead <em>reverses the whole column comparator</em>
     * for a descending sort, which would flip {@code nullsLast} into {@code nullsFirst} (undated rows
     * jumping to the top — the bug noted earlier). So for a descending sort we hand Vaadin a
     * <em>nulls-first</em> comparator: after Vaadin reverses it, the nulls land last and the dates run
     * newest-first.
     */
    private void applyDateNullPolicy(boolean descending) {
        dateColumn.setComparator(rowCmp(dateItemComparator(descending)));
    }

    /**
     * The date comparator handed to the grid for the given direction. Ascending → nulls last (as-is).
     * Descending → nulls FIRST, because the grid reverses this comparator for a descending sort, so the
     * reversed result places nulls last with newest dates first. Package-private + static for testing.
     */
    static Comparator<NewsItem> dateItemComparator(boolean descending) {
        return Comparator.comparing(NewsItem::date, descending
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** Snapshot the current column order/width/visibility and persist it for this user. */
    private void persistColumnLayout() {
        List<UserNewsService.ColumnState> states = new ArrayList<>();
        int pos = 0;
        for (Grid.Column<Row> c : columnOrder) {
            if (c.getKey() == null) continue;
            states.add(new UserNewsService.ColumnState(c.getKey(), pos++, c.getWidth(), c.isVisible()));
        }
        news.saveColumnLayout(subject, states);
    }

    /** Restore this user's saved column order/width/visibility (no-op on first use). */
    private void applyColumnPrefs() {
        List<UserNewsService.ColumnState> prefs = news.columnPrefs(subject);
        if (prefs.isEmpty()) return;

        // Width + visibility per column.
        for (UserNewsService.ColumnState cs : prefs) {
            Grid.Column<Row> c = headlines.getColumnByKey(cs.key());
            if (c == null) continue; // a column that no longer exists — ignore
            if (cs.width() != null) {
                c.setWidth(cs.width());
                c.setFlexGrow(0); // a saved (resized) width is fixed, like RSSOwl
            }
            c.setVisible(cs.visible());
        }

        // Order: saved columns first (in saved order), then any newer columns not yet saved.
        // setColumnOrder requires the full set, so append the leftovers.
        List<Grid.Column<Row>> ordered = new ArrayList<>();
        for (UserNewsService.ColumnState cs : prefs) {
            Grid.Column<Row> c = headlines.getColumnByKey(cs.key());
            if (c != null && !ordered.contains(c)) ordered.add(c);
        }
        for (Grid.Column<Row> c : headlines.getColumns()) {
            if (!ordered.contains(c)) ordered.add(c);
        }
        headlines.setColumnOrder(ordered);
        columnOrder = ordered;
    }

    private void applyGrouping(GroupBy by) {
        children.clear();
        List<Row> roots = new ArrayList<>();
        List<NewsItem> items = displayedItems(); // feed selection narrowed by the search box
        if (by == GroupBy.NONE) {
            for (NewsItem n : items) roots.add(new Row.ItemRow(n));
            headlines.setItems(roots, r -> List.of());
        } else {
            for (Bucket b : Grouping.group(items, by)) {
                Row.GroupRow g = new Row.GroupRow(b.key(), b.label(), b.colorHint(),
                        b.orderIndex(), b.items().size());
                List<Row> kids = new ArrayList<>();
                for (NewsItem n : b.items()) kids.add(new Row.ItemRow(n));
                children.put(g, kids);
                roots.add(g);
            }
            headlines.setItems(roots, r -> r instanceof Row.GroupRow g
                    ? children.getOrDefault(g, List.of()) : List.of());
            headlines.expandRecursively(roots, 1);
        }
        if (dateColumn != null) {
            applyDateNullPolicy(true); // forced default = date DESC, undated rows last
            headlines.sort(GridSortOrder.desc(dateColumn).build());
        }
    }

    // --- bottom-right pane: reader ---

    private Div buildReactiveReader() {
        Div reader = new Div();
        reader.setSizeFull();
        reader.addClassName("article-reader"); // forces an always-visible scrollbar (see styles.css)
        reader.getStyle().set("padding", "1rem").set("overflow", "auto");
        Signal.effect(reader, () -> {
            NewsItem it = selected.get();
            reader.removeAll();
            if (it == null) {
                Span hint = new Span("Select a headline to read it.");
                hint.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");
                reader.add(hint);
                return;
            }
            H3 title = new H3(it.title());
            if (it.labelColor() != null) title.getStyle().set("color", it.labelColor());
            // Meta line: date · feed · author (author omitted when blank; no raw state enum).
            String metaText = (it.date() == null ? "—" : shortDate(it.date())) + "  ·  " + it.feed()
                    + (it.author() == null || it.author().isBlank() ? "" : "  ·  " + it.author());
            Paragraph meta = new Paragraph(metaText);
            meta.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");
            reader.add(title, meta);

            // The feed's own article HTML, rendered inline (RSSOwl uses an embedded browser; here it's
            // a sanitized Html fragment — Vaadin's Html does NOT sanitize, so we clean it with jsoup).
            String body = ArticleHtml.sanitize(it.content());
            if (!body.isBlank()) {
                reader.add(new Html("<div class=\"article-content\">" + body + "</div>"));
            }

            reader.add(buildReaderFooter(it)); // per-article action bar (RSSOwl's footer toolbar)
        });
        return reader;
    }

    /** The per-article action bar under the reader (RSSOwl shows Sticky · Label · … · Full Content).
     *  Wired to the same per-user actions as the grid, acting on the shown item. */
    private Component buildReaderFooter(NewsItem it) {
        Row.ItemRow ir = new Row.ItemRow(it);
        Button read = new Button(it.unread() ? "Mark read" : "Mark unread",
                new Icon(it.unread() ? VaadinIcon.ENVELOPE_OPEN : VaadinIcon.ENVELOPE),
                e -> { markReadBulk(List.of(ir), it.unread()); selected.set(it); });
        Button sticky = new Button(it.sticky() ? "Remove sticky" : "Make sticky", new Icon(VaadinIcon.PIN),
                e -> { setStickyBulk(List.of(ir), !it.sticky()); selected.set(it); });

        // Label menu built from the user's managed labels (toggles the label on this item).
        MenuBar labelBar = new MenuBar();
        labelBar.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_SMALL);
        SubMenu labelMenu = labelBar.addItem(new Icon(VaadinIcon.TAG), "Label").getSubMenu();
        for (NewsItem.LabelRef lr : userLabels) {
            labelMenu.addItem(lr.name(), e -> { toggleLabelBulk(List.of(ir), lr.id()); selected.set(it); });
        }
        labelMenu.addItem("Clear labels", e -> { clearLabelsBulk(List.of(ir)); selected.set(it); });

        Anchor open = new Anchor(it.link(), "Full Content ↗");
        open.setTarget("_blank");
        open.getStyle().set("align-self", "center").set("font-size", "var(--lumo-font-size-s)");

        for (Button b : new Button[]{read, sticky}) b.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        HorizontalLayout footer = new HorizontalLayout(sticky, labelBar, read, open);
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.getStyle().set("margin-top", "1rem").set("border-top", "1px solid var(--lumo-contrast-10pct)")
                .set("padding-top", "0.5rem").set("flex-wrap", "wrap");
        return footer;
    }

    private void buildContextMenu() {
        GridContextMenu<Row> menu = headlines.addContextMenu();
        menu.addItem("Open original", e -> itemOf(e.getItem()).ifPresent(this::openLink));
        menu.addSeparator();
        // Read/sticky/label act on the whole selection when the right-clicked row is part of it,
        // otherwise on just that row (RSSOwl applies news actions to the selected set).
        GridMenuItem<Row> read = menu.addItem("Mark read", e -> e.getItem().ifPresent(r ->
                markReadBulk(actionTargets(r), r instanceof Row.ItemRow ir && ir.news().unread())));
        GridMenuItem<Row> sticky = menu.addItem("Make sticky", e -> e.getItem().ifPresent(r ->
                setStickyBulk(actionTargets(r), !(r instanceof Row.ItemRow ir && ir.news().sticky()))));

        // Label submenu (RSSOwl: assign the user's labels to news, multi-label). Built from the user's
        // managed labels and rebuilt on label CRUD; each item toggles that label across the target rows.
        labelRoot = menu.addItem("Label");
        populateLabelSubmenu();

        // Add-to-bin submenu (RSSOwl: copy news into a bin) + remove-from-bin when a bin is open.
        binRoot = menu.addItem("Add to bin");
        populateBinSubmenu();
        GridMenuItem<Row> removeFromBin = menu.addItem("Remove from this bin", e -> e.getItem()
                .ifPresent(r -> removeFromBinBulk(actionTargets(r))));

        menu.setDynamicContentHandler(row -> {
            if (!(row instanceof Row.ItemRow ir)) return false; // no menu on group rows
            int n = actionTargets(row).size();
            String suffix = n > 1 ? " (" + n + ")" : ""; // show how many rows the action will affect
            read.setText((ir.news().unread() ? "Mark read" : "Mark unread") + suffix);
            sticky.setText((ir.news().sticky() ? "Remove sticky" : "Make sticky") + suffix);
            labelRoot.setText("Label" + suffix);
            binRoot.setText("Add to bin" + suffix);
            removeFromBin.setVisible(currentBinId != null); // only meaningful while viewing a bin
            return true;
        });
    }

    /** (Re)build the "Add to bin" submenu from the user's bins; each adds the target rows to that bin.
     *  Plus "New bin…" which creates a bin and drops the targets in. */
    private void populateBinSubmenu() {
        binRoot.getSubMenu().removeAll();
        for (UserNewsService.BinRef b : news.bins(subject)) {
            binRoot.getSubMenu().addItem(b.name(),
                    e -> e.getItem().ifPresent(r -> addToBinBulk(actionTargets(r), b.id())));
        }
        binRoot.getSubMenu().addItem("New bin…", e -> e.getItem().ifPresent(r -> openNewBinDialog(actionTargets(r))));
    }

    private void addToBinBulk(List<Row.ItemRow> rows, long binId) {
        if (rows.isEmpty()) return;
        news.addToBin(subject, binId, rows.stream().map(ir -> ir.news().id()).toList());
        reloadUserData(); // refresh bin counts (and the bin view if it's open)
        Notification.show("Added " + rows.size() + " to bin");
    }

    private void removeFromBinBulk(List<Row.ItemRow> rows) {
        if (currentBinId == null || rows.isEmpty()) return;
        news.removeFromBin(subject, currentBinId, rows.stream().map(ir -> ir.news().id()).toList());
        reloadUserData();
        Notification.show("Removed " + rows.size() + " from bin");
    }

    /** Prompt for a bin name, create it, and drop the given rows in. */
    private void openNewBinDialog(List<Row.ItemRow> rows) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New bin");
        dialog.setWidth("460px");
        TextField name = new TextField("Name");
        name.setWidthFull();
        dialog.add(name);
        Button create = new Button("Create", e -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true); name.setErrorMessage("A name is required"); return;
            }
            long binId = news.createBin(subject, name.getValue().trim());
            if (!rows.isEmpty()) news.addToBin(subject, binId, rows.stream().map(ir -> ir.news().id()).toList());
            reloadUserData();
            populateBinSubmenu();
            dialog.close();
            Notification.show("Bin created");
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), create);
        dialog.open();
    }

    /** (Re)build the Label submenu from the user's managed labels: one toggle per label, plus clear +
     *  manage. Called at startup and after label CRUD. */
    private void populateLabelSubmenu() {
        labelRoot.getSubMenu().removeAll();
        for (NewsItem.LabelRef lr : userLabels) {
            labelRoot.getSubMenu().addItem(lr.name(),
                    e -> e.getItem().ifPresent(r -> toggleLabelBulk(actionTargets(r), lr.id())));
        }
        labelRoot.getSubMenu().addItem("Clear labels", e -> e.getItem().ifPresent(r -> clearLabelsBulk(actionTargets(r))));
        labelRoot.getSubMenu().addItem("Manage labels…", e -> openLabelsDialog());
    }

    /** Toggle one label across the target rows: add it where missing, or remove it if every row has it. */
    private void toggleLabelBulk(List<Row.ItemRow> rows, long labelId) {
        if (rows.isEmpty()) return;
        boolean allHave = rows.stream().allMatch(ir -> ir.news().labels().stream().anyMatch(l -> l.id() == labelId));
        for (Row.ItemRow ir : rows) {
            java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
            ir.news().labels().forEach(l -> ids.add(l.id()));
            if (allHave) ids.remove(labelId); else ids.add(labelId);
            news.setLabels(subject, ir.news().id(), ids);
            applyLabelsToItem(ir, ids);
        }
        refreshTreeCounts(); // "Labeled News" smart-folder count changed
    }

    private void clearLabelsBulk(List<Row.ItemRow> rows) {
        if (rows.isEmpty()) return;
        for (Row.ItemRow ir : rows) {
            news.setLabels(subject, ir.news().id(), java.util.Set.of());
            applyLabelsToItem(ir, java.util.Set.of());
        }
        refreshTreeCounts();
    }

    /** Reflect a new label-id set onto the in-memory NewsItem (resolving names/colours) and refresh the row. */
    private void applyLabelsToItem(Row.ItemRow ir, java.util.Set<Long> ids) {
        List<NewsItem.LabelRef> resolved = userLabels.stream().filter(l -> ids.contains(l.id())).toList();
        ir.news().setLabels(resolved);
        headlines.getDataProvider().refreshItem(ir);
    }

    private void openLabelsDialog() {
        new LabelsDialog(news, subject, () -> {
            userLabels.clear();
            userLabels.addAll(news.labels(subject));
            populateLabelSubmenu();
            reloadUserData(); // labels may have been recoloured/removed — re-resolve item labels
        }).open();
    }

    /**
     * The headlines an action applies to: the current multi-selection if the right-clicked row is part
     * of it (and there's more than one), otherwise just the right-clicked row. Group rows are excluded.
     */
    private List<Row.ItemRow> actionTargets(Row clicked) {
        // If the right-clicked row is part of a multi-selection, act on the whole selection; else just it.
        if (clicked instanceof Row.ItemRow ir && selectedIds.contains(ir.news().id()) && selectedIds.size() > 1) {
            return displayedItems().stream().filter(n -> selectedIds.contains(n.id()))
                    .map(Row.ItemRow::new).toList();
        }
        return clicked instanceof Row.ItemRow ir2 ? List.of(ir2) : List.of();
    }

    /** Select every item between the anchor and the clicked id, in the currently displayed order
     *  (Shift-range). Falls back to just the clicked id if either isn't in the current view. */
    private void selectRange(long anchorId, long clickId) {
        List<NewsItem> shown = displayedItems();
        int a = -1, b = -1;
        for (int i = 0; i < shown.size(); i++) {
            long id = shown.get(i).id();
            if (id == anchorId) a = i;
            if (id == clickId) b = i;
        }
        if (a < 0 || b < 0) { selectedIds.add(clickId); return; }
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) selectedIds.add(shown.get(i).id());
    }

    /**
     * RSSOwl marks an article read the moment you open it. Called on a plain click (which shows the
     * item in the reader): de-bolds the row and drops the feed/folder unread count by one. No-op if the
     * item is already read.
     */
    private void markReadOnOpen(NewsItem it) {
        if (it == null || !it.unread()) return;
        it.setRead(true);
        news.setRead(subject, it.id(), true);            // persist per-user
        headlines.getDataProvider().refreshItem(new Row.ItemRow(it)); // de-bold this row
        refreshTreeCounts();                             // e.g. Fast Company (35) -> (34)
    }

    /** Cancel a pending keyboard auto-read dwell, if any. */
    private void cancelDwell() {
        if (pendingDwell != null) { pendingDwell.cancel(false); pendingDwell = null; }
    }

    /** Enter on the keyboard-focused headline: show it in the reader, then toggle its read state
     *  (unread → read now; read → mark unread). Independent of the auto-read delay. */
    private void onEnterKey() {
        cancelDwell();
        if (focusedId == null) return;
        long id = focusedId;
        NewsItem item = displayedItems().stream().filter(n -> n.id() == id).findFirst().orElse(null);
        if (item == null) return;
        selectedIds.clear(); selectedIds.add(id); selectionAnchorId = id;
        selected.set(item);                          // show in reader
        boolean nowRead = news.toggleRead(subject, id); // flip + persist; returns the new state
        item.setRead(nowRead);                       // keep the in-memory item in sync
        headlines.getDataProvider().refreshItem(new Row.ItemRow(item));
        refreshTreeCounts();
        headlines.getDataProvider().refreshAll();
    }

    private void markReadBulk(List<Row.ItemRow> rows, boolean read) {
        if (rows.isEmpty()) return;
        for (Row.ItemRow ir : rows) {
            ir.news().setRead(read);
            news.setRead(subject, ir.news().id(), read); // persist per-user
            headlines.getDataProvider().refreshItem(ir);
        }
        refreshTreeCounts(); // unread counts changed
    }

    private void setStickyBulk(List<Row.ItemRow> rows, boolean sticky) {
        if (rows.isEmpty()) return;
        for (Row.ItemRow ir : rows) {
            ir.news().setSticky(sticky);
            news.setSticky(subject, ir.news().id(), sticky); // persist per-user
            headlines.getDataProvider().refreshItem(ir);
        }
        refreshTreeCounts(); // "Sticky News" smart-folder count changed
    }

    // --- cell components ---

    private Component titleComponent(Row row) {
        if (row instanceof Row.GroupRow g) {
            Span s = new Span(g.label() + "  (" + g.count() + ")");
            s.getStyle().set("font-weight", "600");
            return s;
        }
        NewsItem n = ((Row.ItemRow) row).news();
        Span s = new Span(n.title());
        if (n.labelColor() != null) s.getStyle().set("color", n.labelColor());
        // The read/unread state icon lives on the Title (as in RSSOwl), followed by any label chips.
        HorizontalLayout cell = new HorizontalLayout(stateIcon(n), s);
        cell.setSpacing(false);
        cell.setAlignItems(FlexComponent.Alignment.CENTER);
        cell.getStyle().set("gap", "6px");
        for (NewsItem.LabelRef lr : n.labels()) {
            Span chip = new Span();
            chip.getElement().setAttribute("title", lr.name());
            chip.getStyle().set("display", "inline-block").set("width", "9px").set("height", "9px")
                    .set("border-radius", "50%").set("background-color", lr.color()).set("margin-left", "2px");
            cell.add(chip);
        }
        return cell;
    }

    private Icon stateIcon(NewsItem it) {
        Icon icon = new Icon(it.unread() ? VaadinIcon.CIRCLE : VaadinIcon.CIRCLE_THIN);
        icon.setSize("11px");
        icon.getStyle().set("color", switch (it.state()) {
            case NEW -> "#1565c0";
            case UPDATED -> "#ef6c00";
            case UNREAD -> "var(--vaadin-text-color, #333)";
            case READ -> "var(--vaadin-text-color-secondary, #999)";
        });
        icon.getElement().setAttribute("title", it.state().name());
        return icon;
    }

    // --- actions ---

    /** Style a toolbar toggle button to reflect its on/off state: a filled container surface + bold
     *  label when active, plain tertiary (secondary text) when off. Uses base theme tokens so it adapts
     *  across themes and color schemes. Sets {@code aria-pressed} for accessibility. */
    private static void styleViewToggle(Button b, boolean on) {
        b.getStyle()
                .set("border-radius", "var(--vaadin-radius-m)")
                .set("background", on ? "var(--vaadin-background-container-strong)" : "transparent")
                .set("color", on ? "var(--vaadin-text-color)" : "var(--vaadin-text-color-secondary)")
                .set("font-weight", on ? "var(--aura-font-weight-semibold, 600)" : "var(--aura-font-weight-regular, 400)");
        b.getElement().setAttribute("aria-pressed", Boolean.toString(on));
    }

    private void openLink(NewsItem it) {
        if (it != null) UI.getCurrent().getPage().open(it.link(), "_blank");
    }

    private Optional<NewsItem> itemOf(Optional<Row> row) {
        return row.filter(r -> r instanceof Row.ItemRow).map(r -> ((Row.ItemRow) r).news());
    }

    /** Predicate behind a saved-search smart folder. */
    private java.util.function.Predicate<NewsItem> savedPredicate(String key) {
        return switch (key) {
            case "unread" -> NewsItem::unread;
            case "today" -> n -> n.date() != null
                    && n.date().toLocalDate().equals(java.time.LocalDate.now());
            case "attachments" -> NewsItem::attachments;
            case "sticky" -> NewsItem::sticky;
            case "labeled" -> n -> n.labelColor() != null; // user-assigned a label
            default -> n -> false;
        };
    }

    /**
     * The items currently shown. With no search term, it's the feed/folder selection. With a term, it's
     * a Lucene full-text search across the user's <em>whole</em> archive — title, body and author, ranked
     * by relevance (RSSOwl-style: supports phrases {@code "..."}, boolean {@code AND/OR/NOT}, and
     * field-scoped {@code title:...} queries) — so search reaches beyond the loaded selection.
     */
    private List<NewsItem> displayedItems() {
        // RSSOwl Filter Bar: narrow the CURRENT selection live by the scoped filter text (default: title).
        List<NewsItem> base = currentItems;
        if (!searchTerm.isBlank()) {
            String t = searchTerm.toLowerCase();
            base = base.stream().filter(n -> HeadlineFilter.matches(n, filterScope, t)).toList();
        }
        if (unreadOnly) return base.stream().filter(NewsItem::unread).toList(); // RSSOwl's "Unread" view mode
        return base;
    }

    private Comparator<Row> rowCmp(Comparator<NewsItem> itemCmp) {
        return (a, b) -> {
            if (a instanceof Row.GroupRow ga && b instanceof Row.GroupRow gb) {
                return Integer.compare(ga.orderIndex(), gb.orderIndex());
            }
            if (a instanceof Row.ItemRow ia && b instanceof Row.ItemRow ib) {
                return itemCmp.compare(ia.news(), ib.news());
            }
            return 0;
        };
    }
}