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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.example.headlines.service.AuthenticationRequiredException;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** RSSOwl's default saved-search smart folders: {display name, predicate key}. */
    private static final String[][] SAVED = {
            {"Unread News", "unread"}, {"Today's News", "today"},
            {"News with Attachments", "attachments"}, {"Sticky News", "sticky"},
            {"Labeled News", "labeled"}};

    /** RSSOwl's default labels: {name, CSS colour}. Assigned per-article via the context menu. */
    private static final String[][] LABELS = {
            {"Important", "#c62828"}, {"Work", "#1565c0"}, {"Personal", "#2e7d32"},
            {"To Do", "#ef6c00"}, {"Later", "#6a1b9a"}};

    private List<NewsItem> allItems; // reloaded after add/unsubscribe
    private List<NewsItem> currentItems; // the feed/folder/smart-folder selection
    private String searchTerm = "";      // live headline filter (empty = no filter)
    private GroupBy currentGroupBy = GroupBy.NONE;

    private final TreeGrid<FeedNode> feedTree = new TreeGrid<>();
    private TreeData<FeedNode> feedData;
    private TreeDataProvider<FeedNode> feedDataProvider;
    private FeedNode draggedNode;
    private final TreeGrid<Row> headlines = new TreeGrid<>();
    private final ValueSignal<NewsItem> selected = new ValueSignal<NewsItem>((NewsItem) null);
    private final Map<Row.GroupRow, List<Row>> children = new HashMap<>();
    private Grid.Column<Row> dateColumn;
    private List<Grid.Column<Row>> columnOrder; // current left-to-right order, for persistence

    // Auto-mark-read: RSSOwl marks the displayed article read after a short delay. A single shared
    // daemon scheduler runs the delay off the UI thread; @Push (see Application) carries the update back.
    private static final long AUTO_READ_DELAY_MS = 2000;
    private static final java.util.concurrent.ScheduledExecutorService AUTO_READ_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auto-mark-read");
                t.setDaemon(true);
                return t;
            });
    private java.util.concurrent.ScheduledFuture<?> pendingAutoRead;
    private boolean autoReadEnabled = true;

    private final UserNewsService news;
    private final FeedFetchService feedFetch;
    private final AuthenticationContext authContext;
    private final String subject;      // Keycloak subject — the per-user key
    private final String displayName;  // for the header

    public HeadlinesView(UserNewsService news, FeedFetchService feedFetch, AuthenticationContext authContext) {
        this.news = news;
        this.feedFetch = feedFetch;
        this.authContext = authContext;
        OidcUser user = authContext.getAuthenticatedUser(OidcUser.class)
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
        this.subject = user.getSubject();
        this.displayName = user.getPreferredUsername() != null ? user.getPreferredUsername() : user.getName();

        // First login for this user? Seed their default subscriptions, then load their headlines.
        news.ensureSeeded(subject);

        setSizeFull();
        this.allItems = news.newsItems(subject);
        this.currentItems = allItems;

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

        // left pane: an "Add feed" bar above the feeds tree
        Button addFeed = new Button("Add feed", VaadinIcon.PLUS.create(), e -> openAddFeedDialog());
        addFeed.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout feedBar = new HorizontalLayout(addFeed);
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

        // Don't let a queued auto-mark-read fire after the user navigates away / the UI is gone.
        addDetachListener(e -> {
            if (pendingAutoRead != null) pendingAutoRead.cancel(false);
        });
    }

    // --- left pane: feeds navigation tree (RSSOwl's BookMarkExplorer) ---

    private void configureFeedTree() {
        feedTree.setSizeFull();
        feedTree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);

        // Build the tree from the user's persisted subscriptions (folder + drag order).
        buildFeedTreeData();

        // FLATTENED keeps scroll/expansion stable across refreshAll() after a drag (see Vaadin docs).
        feedDataProvider = new TreeDataProvider<>(feedData, HierarchyFormat.FLATTENED);
        feedTree.addHierarchyColumn(FeedNode::label).setHeader("Feeds");
        feedTree.setDataProvider(feedDataProvider);
        feedTree.setPartNameGenerator(n -> n instanceof FeedNode.Category ? "feed-category"
                : n instanceof FeedNode.Saved ? "feed-saved" : null);
        enableFeedDragAndDrop();

        feedTree.addSelectionListener(e -> {
            FeedNode sel = e.getFirstSelectedItem().orElse(null);
            if (sel instanceof FeedNode.Category c) {
                // A folder shows its own feeds plus everything in its sub-folders (path prefix match).
                String p = c.path();
                currentItems = allItems.stream().filter(n -> n.category() != null
                        && (n.category().equals(p) || n.category().startsWith(p + "/"))).toList();
            } else if (sel instanceof FeedNode.Feed f) {
                currentItems = allItems.stream().filter(n -> f.name().equals(n.feed())).toList();
            } else if (sel instanceof FeedNode.Saved sv) {
                currentItems = allItems.stream().filter(savedPredicate(sv.key())).toList();
            } else {
                currentItems = allItems;
            }
            applyGrouping(currentGroupBy);
        });

        // Right-click a channel: set HTTP credentials or unsubscribe (RSSOwl bookmark actions).
        // Feeds only.
        GridContextMenu<FeedNode> feedMenu = feedTree.addContextMenu();
        feedMenu.addItem("Set credentials…", e -> e.getItem()
                .filter(n -> n instanceof FeedNode.Feed)
                .ifPresent(n -> openCredentialsDialog((FeedNode.Feed) n)));
        feedMenu.addItem("Unsubscribe", e -> e.getItem().ifPresent(this::unsubscribe));
        feedMenu.setDynamicContentHandler(node -> node instanceof FeedNode.Feed);
    }

    private void openAddFeedDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Add feed");
        TextField url = new TextField("Feed URL");
        url.setWidthFull();
        url.setPlaceholder("https://example.com/rss");
        TextField title = new TextField("Title (optional)");
        title.setWidthFull();
        TextField folder = new TextField("Folder (optional)");
        folder.setWidthFull();
        // Optional HTTP credentials, like RSSOwl's per-feed authentication.
        TextField user = new TextField("Username (optional)");
        user.setWidthFull();
        PasswordField pass = new PasswordField("Password (optional)");
        pass.setWidthFull();
        VerticalLayout form = new VerticalLayout(url, title, folder, user, pass);
        form.setPadding(false);
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

    /** Login dialog for a feed's HTTP credentials (RSSOwl: "Feed requires authentication"). */
    private void openCredentialsDialog(FeedNode.Feed feed) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Feed requires authentication");
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
        buildFeedTreeData();
        feedDataProvider = new TreeDataProvider<>(feedData, HierarchyFormat.FLATTENED);
        feedTree.setDataProvider(feedDataProvider);
        this.currentItems = allItems;
        applyGrouping(currentGroupBy);
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
        feedData = new TreeData<>();
        Map<String, Long> countByFeed = allItems.stream()
                .collect(Collectors.groupingBy(NewsItem::feed, Collectors.counting()));

        // Feeds in OPML order; each folder records its descendants' total count and earliest position.
        List<UserNewsService.FeedRef> ordered = new ArrayList<>(news.feedRefs(subject));
        ordered.sort(Comparator.comparingInt(UserNewsService.FeedRef::position));

        Map<String, List<UserNewsService.FeedRef>> directFeeds = new LinkedHashMap<>(); // path ("" = root) -> feeds
        Map<String, Integer> countByPath = new HashMap<>(); // folder path -> total items (incl. sub-folders)
        Map<String, Integer> posByPath = new HashMap<>();    // folder path -> min OPML position (ordering key)
        Set<String> catPaths = new LinkedHashSet<>();
        for (UserNewsService.FeedRef r : ordered) {
            String folder = folderOf(r.folder()); // "" for a loose top-level channel
            int cnt = countByFeed.getOrDefault(r.title(), 0L).intValue();
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
        addFeedTreeLevel("", null, directFeeds, catPaths, countByPath, posByPath, countByFeed, savedOrder);

        for (String[] s : SAVED) {
            int c = (int) allItems.stream().filter(savedPredicate(s[1])).count();
            feedData.addItem(null, new FeedNode.Saved(s[0], s[1], c));
        }
    }

    /** Adds the sub-folders and feeds directly under {@code parentPath} (root when empty), interleaved
     *  in OPML order — recursing into each sub-folder. */
    private void addFeedTreeLevel(String parentPath, FeedNode.Category parentNode,
            Map<String, List<UserNewsService.FeedRef>> directFeeds, Set<String> catPaths,
            Map<String, Integer> countByPath, Map<String, Integer> posByPath,
            Map<String, Long> countByFeed, List<String> savedOrder) {
        // Combined, position-ordered list of this level's children: sub-folder paths and feed refs.
        List<Object> children = new ArrayList<>();
        catPaths.stream().filter(p -> isDirectChild(parentPath, p)).forEach(children::add);
        children.addAll(directFeeds.getOrDefault(parentPath, List.of()));
        children.sort(Comparator.comparingInt(c -> levelOrderKey(c, parentPath, posByPath, savedOrder)));

        for (Object child : children) {
            if (child instanceof String cp) {
                FeedNode.Category cat = new FeedNode.Category(cp, lastSegment(cp), countByPath.getOrDefault(cp, 0));
                feedData.addItem(parentNode, cat);
                addFeedTreeLevel(cp, cat, directFeeds, catPaths, countByPath, posByPath, countByFeed, savedOrder);
            } else {
                UserNewsService.FeedRef r = (UserNewsService.FeedRef) child;
                feedData.addItem(parentNode, new FeedNode.Feed(r.title(),
                        parentPath.isEmpty() ? "Uncategorized" : parentPath,
                        countByFeed.getOrDefault(r.title(), 0L).intValue(), r.subscriptionId(),
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

        // Live headline search (title / author / feed), narrowing the current selection.
        TextField search = new TextField();
        search.setPlaceholder("Search headlines…");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.addValueChangeListener(e -> {
            searchTerm = e.getValue() == null ? "" : e.getValue().trim();
            applyGrouping(currentGroupBy);
        });

        // Column visibility menu (RSSOwl lets you show/hide news columns). Title stays mandatory.
        MenuBar columnsMenu = new MenuBar();
        columnsMenu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_SMALL);
        SubMenu cols = columnsMenu.addItem("Columns").getSubMenu();
        addColumnToggle(cols, "author", "Author");
        addColumnToggle(cols, "feed", "Feed");
        addColumnToggle(cols, "date", "Date");

        // Auto-mark-read toggle (RSSOwl marks the displayed article read after a short delay).
        Checkbox autoRead = new Checkbox("Mark read after viewing");
        autoRead.setValue(autoReadEnabled);
        autoRead.getStyle().set("align-self", "center");
        autoRead.addValueChangeListener(e -> {
            autoReadEnabled = e.getValue();
            if (!autoReadEnabled && pendingAutoRead != null) pendingAutoRead.cancel(false);
            else scheduleAutoMarkRead(selected.peek()); // re-arm; peek() = read signal outside an effect
        });

        // Signed-in identity + logout (multi-user: proves whose data this is).
        Span who = new Span("Signed in as " + displayName);
        who.getStyle().set("align-self", "center").set("color", "var(--vaadin-text-color-secondary, #666)");
        Button logout = new Button("Log out", e -> authContext.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout bar = new HorizontalLayout(groupBy, search, columnsMenu, autoRead, who, logout);
        bar.setAlignItems(FlexComponent.Alignment.END);
        bar.setWidthFull();
        bar.setFlexGrow(1, who); // push logout to the right
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
        // RSSOwl's news table is multi-select (Ctrl/Shift-click) for bulk actions; the reader shows the
        // first of the selection. Context-menu actions below operate on the whole selection.
        headlines.setSelectionMode(TreeGrid.SelectionMode.MULTI);

        headlines.addComponentColumn(row -> itemOnly(row, this::stateIcon))
                .setHeader("").setKey("status").setWidth("46px").setFlexGrow(0)
                .setComparator(rowCmp(Comparator.comparingInt(NewsItem::statusRank))).setSortable(true);

        headlines.addComponentHierarchyColumn(this::titleComponent)
                .setHeader("Title").setKey("title").setFlexGrow(3).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::title, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        headlines.addColumn(row -> row instanceof Row.ItemRow ir ? ir.news().author() : "")
                .setHeader("Author").setKey("author").setFlexGrow(1).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::author, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        headlines.addColumn(row -> row instanceof Row.ItemRow ir ? ir.news().feed() : "")
                .setHeader("Feed").setKey("feed").setFlexGrow(1).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::feed, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        dateColumn = headlines.addColumn(row -> {
                    if (row instanceof Row.ItemRow ir && ir.news().date() != null) {
                        return DATE_FMT.format(ir.news().date());
                    }
                    return "";
                })
                .setHeader("Date").setKey("date").setWidth("160px").setFlexGrow(0).setResizable(true)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::date,
                        Comparator.nullsLast(Comparator.naturalOrder())))).setSortable(true);

        headlines.addComponentColumn(row -> row instanceof Row.ItemRow ir ? readToggle(ir) : new Span())
                .setHeader("").setKey("read").setWidth("46px").setFlexGrow(0);
        headlines.addComponentColumn(row -> row instanceof Row.ItemRow ir ? stickyToggle(ir) : new Span())
                .setHeader("").setKey("sticky").setWidth("46px").setFlexGrow(0);

        headlines.setPartNameGenerator(row -> {
            if (row instanceof Row.GroupRow) return "group";
            Row.ItemRow ir = (Row.ItemRow) row;
            StringBuilder sb = new StringBuilder();
            if (ir.news().unread()) sb.append("unread");
            if (ir.news().sticky()) sb.append(sb.isEmpty() ? "" : " ").append("sticky");
            return sb.isEmpty() ? null : sb.toString();
        });

        // Click a headline to read it (and arm auto-mark-read). The grid is MULTI-select, where a
        // row-body click does NOT change selection (only the checkbox column does) — so the reader is
        // wired to item-click, decoupling "read this one" from "select these for a bulk action".
        headlines.addItemClickListener(e -> {
            if (e.getItem() instanceof Row.ItemRow ir) {
                selected.set(ir.news());
                scheduleAutoMarkRead(ir.news());
            }
        });
        headlines.addItemDoubleClickListener(e -> {
            if (e.getItem() instanceof Row.ItemRow ir) openLink(ir.news());
        });

        // Column customisation (RSSOwl persists per-column order/width/visibility): let the user
        // drag column headers to reorder and drag header edges to resize; persist either change.
        headlines.setColumnReorderingAllowed(true);
        columnOrder = new ArrayList<>(headlines.getColumns());
        headlines.addColumnReorderListener(e -> {
            columnOrder = new ArrayList<>(e.getColumns());
            persistColumnLayout();
        });
        headlines.addColumnResizeListener(e -> persistColumnLayout());

        buildContextMenu();
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
            headlines.sort(GridSortOrder.desc(dateColumn).build());
        }
    }

    // --- bottom-right pane: reader ---

    private Div buildReactiveReader() {
        Div reader = new Div();
        reader.setSizeFull();
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
            Paragraph meta = new Paragraph(
                    (it.date() == null ? "—" : DATE_FMT.format(it.date()))
                            + "  ·  " + it.feed() + "  ·  " + it.author() + "  ·  " + it.state());
            meta.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");
            Anchor link = new Anchor(it.link(), "Open original ↗");
            link.setTarget("_blank");
            reader.add(title, meta, link);

            // The feed's own article HTML, rendered inline (RSSOwl uses an embedded browser; here it's
            // a sanitized Html fragment — Vaadin's Html does NOT sanitize, so we clean it with jsoup).
            String body = ArticleHtml.sanitize(it.content());
            if (!body.isBlank()) {
                reader.add(new Html("<div class=\"article-content\">" + body + "</div>"));
            }
        });
        return reader;
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

        // Label submenu (RSSOwl: assign a coloured label to a news item).
        GridMenuItem<Row> label = menu.addItem("Label");
        for (String[] lbl : LABELS) {
            String color = lbl[1];
            label.getSubMenu().addItem(lbl[0], e -> e.getItem().ifPresent(r -> applyLabelBulk(actionTargets(r), color)));
        }
        label.getSubMenu().addItem("Remove label", e -> e.getItem().ifPresent(r -> applyLabelBulk(actionTargets(r), null)));

        menu.setDynamicContentHandler(row -> {
            if (!(row instanceof Row.ItemRow ir)) return false; // no menu on group rows
            int n = actionTargets(row).size();
            String suffix = n > 1 ? " (" + n + ")" : ""; // show how many rows the action will affect
            read.setText((ir.news().unread() ? "Mark read" : "Mark unread") + suffix);
            sticky.setText((ir.news().sticky() ? "Remove sticky" : "Make sticky") + suffix);
            label.setText("Label" + suffix);
            return true;
        });
    }

    /**
     * The headlines an action applies to: the current multi-selection if the right-clicked row is part
     * of it (and there's more than one), otherwise just the right-clicked row. Group rows are excluded.
     */
    private List<Row.ItemRow> actionTargets(Row clicked) {
        Set<Row> selectedRows = headlines.getSelectedItems();
        if (clicked != null && selectedRows.contains(clicked) && selectedRows.size() > 1) {
            return selectedRows.stream().filter(r -> r instanceof Row.ItemRow)
                    .map(r -> (Row.ItemRow) r).toList();
        }
        return clicked instanceof Row.ItemRow ir ? List.of(ir) : List.of();
    }

    private void markReadBulk(List<Row.ItemRow> rows, boolean read) {
        for (Row.ItemRow ir : rows) {
            ir.news().setRead(read);
            news.setRead(subject, ir.news().id(), read); // persist per-user
            headlines.getDataProvider().refreshItem(ir);
        }
    }

    private void setStickyBulk(List<Row.ItemRow> rows, boolean sticky) {
        for (Row.ItemRow ir : rows) {
            ir.news().setSticky(sticky);
            news.setSticky(subject, ir.news().id(), sticky); // persist per-user
            headlines.getDataProvider().refreshItem(ir);
        }
    }

    /** Assign (or clear, when {@code color} is null) a user label across the rows, persisted per user. */
    private void applyLabelBulk(List<Row.ItemRow> rows, String color) {
        for (Row.ItemRow ir : rows) {
            ir.news().setLabelColor(color);
            news.setLabel(subject, ir.news().id(), color);
            headlines.getDataProvider().refreshItem(ir);
        }
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
        return s;
    }

    private Component itemOnly(Row row, java.util.function.Function<NewsItem, Component> fn) {
        return row instanceof Row.ItemRow ir ? fn.apply(ir.news()) : new Span();
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

    private Button readToggle(Row.ItemRow ir) {
        NewsItem it = ir.news();
        Button b = new Button(new Icon(it.unread() ? VaadinIcon.ENVELOPE : VaadinIcon.ENVELOPE_OPEN));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        b.getElement().setAttribute("title", it.unread() ? "Mark read" : "Mark unread");
        b.addClickListener(e -> toggleReadAndRefresh(ir));
        return b;
    }

    private Button stickyToggle(Row.ItemRow ir) {
        NewsItem it = ir.news();
        Button b = new Button(new Icon(VaadinIcon.PIN));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        b.getElement().getStyle().set("color",
                it.sticky() ? "#c9a200" : "var(--vaadin-text-color-secondary, #bbb)");
        b.getElement().setAttribute("title", it.sticky() ? "Remove sticky" : "Make sticky");
        b.addClickListener(e -> toggleStickyAndRefresh(ir));
        return b;
    }

    // --- actions ---

    /**
     * Arm (or cancel) the auto-mark-read timer for the newly displayed headline. After
     * {@link #AUTO_READ_DELAY_MS} the item is marked read — but only if it's still the selected item
     * and still unread, so quickly skipping past articles doesn't mark them. The delay runs off the UI
     * thread on a shared scheduler; the result is pushed back via {@code @Push} and {@code ui.access}.
     */
    private void scheduleAutoMarkRead(NewsItem shown) {
        if (pendingAutoRead != null) {
            pendingAutoRead.cancel(false);
            pendingAutoRead = null;
        }
        if (!autoReadEnabled || shown == null || !shown.unread()) return;
        UI ui = UI.getCurrent();
        long id = shown.id();
        pendingAutoRead = AUTO_READ_SCHEDULER.schedule(() -> ui.access(() -> {
            // peek(), not get(): we're not inside a Signal.effect, so get() would throw
            // "Signal.get() was called outside a reactive context" (Vaadin 25 Signals rule).
            NewsItem cur = selected.peek();
            if (cur != null && cur.id() == id && cur.unread()) {
                cur.setRead(true);
                news.setRead(subject, id, true); // persist per-user
                headlines.getDataProvider().refreshItem(new Row.ItemRow(cur));
            }
        }), AUTO_READ_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void toggleReadAndRefresh(Row row) {
        if (row instanceof Row.ItemRow ir) {
            ir.news().toggleRead();
            news.setRead(subject, ir.news().id(), !ir.news().unread()); // persist per-user
            headlines.getDataProvider().refreshItem(ir);
        }
    }

    private void toggleStickyAndRefresh(Row row) {
        if (row instanceof Row.ItemRow ir) {
            ir.news().toggleSticky();
            news.setSticky(subject, ir.news().id(), ir.news().sticky()); // persist per-user
            headlines.getDataProvider().refreshItem(ir);
        }
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

    /** The items currently shown: the feed/folder selection, narrowed by the search box (if any). */
    private List<NewsItem> displayedItems() {
        if (searchTerm.isBlank()) {
            return currentItems;
        }
        String q = searchTerm.toLowerCase();
        return currentItems.stream().filter(n -> contains(n.title(), q)
                || contains(n.author(), q) || contains(n.feed(), q)).toList();
    }

    private static boolean contains(String s, String lowerQuery) {
        return s != null && s.toLowerCase().contains(lowerQuery);
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