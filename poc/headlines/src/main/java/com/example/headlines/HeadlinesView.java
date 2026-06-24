package com.example.headlines;

import com.example.headlines.Grouping.Bucket;
import com.example.headlines.Grouping.GroupBy;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider.HierarchyFormat;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
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

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * POC matching RSSOwl(nix)'s three-pane structure: left = a feeds-navigation tree
 * (Category → Feed, with counts), top-right = the headlines {@link TreeGrid} (sortable, groupable),
 * bottom-right = the article reader. Live headlines come from RSSOwl's default feeds
 * ({@link FeedService}); selection→reader is wired with Vaadin Signals.
 */
@Route("")
@PageTitle("Headlines — SWT→Vaadin POC")
public class HeadlinesView extends Div {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Big outlets shown as loose top-level channels too (they also live in folders), like RSSOwl.
     *  Names must match the feed titles in feeds.opml. */
    private static final java.util.Set<String> FEATURED = java.util.Set.of(
            "BBC News", "NYT — Home", "Guardian World", "TechCrunch", "Wired");

    /** RSSOwl's default saved-search smart folders: {display name, predicate key}. */
    private static final String[][] SAVED = {
            {"Unread News", "unread"}, {"Today's News", "today"},
            {"News with Attachments", "attachments"}, {"Sticky News", "sticky"},
            {"Labeled News", "labeled"}};

    private final List<NewsItem> allItems;
    private List<NewsItem> currentItems;
    private GroupBy currentGroupBy = GroupBy.NONE;

    private final TreeGrid<FeedNode> feedTree = new TreeGrid<>();
    private TreeData<FeedNode> feedData;
    private TreeDataProvider<FeedNode> feedDataProvider;
    private FeedNode.Feed draggedFeed;
    private final TreeGrid<Row> headlines = new TreeGrid<>();
    private final ValueSignal<NewsItem> selected = new ValueSignal<NewsItem>((NewsItem) null);
    private final Map<Row.GroupRow, List<Row>> children = new HashMap<>();
    private com.vaadin.flow.component.grid.Grid.Column<Row> dateColumn;

    public HeadlinesView(FeedService feedService) {
        setSizeFull();
        this.allItems = feedService.items();
        this.currentItems = allItems;

        configureFeedTree();
        configureHeadlines();
        applyGrouping(GroupBy.NONE);

        Div reader = buildReactiveReader();

        // top-right (headlines + a small toolbar) over bottom-right (reader)
        VerticalLayout headlinesPane = new VerticalLayout(buildToolbar(feedService), headlines);
        headlinesPane.setSizeFull();
        headlinesPane.setPadding(false);
        headlinesPane.setSpacing(false);
        headlinesPane.setFlexGrow(1, headlines);

        SplitLayout right = new SplitLayout(headlinesPane, reader);
        right.setOrientation(SplitLayout.Orientation.VERTICAL);
        right.setSplitterPosition(60);
        right.setSizeFull();

        // left feeds tree | right (headlines / reader)
        SplitLayout outer = new SplitLayout(feedTree, right);
        outer.setOrientation(SplitLayout.Orientation.HORIZONTAL);
        outer.setSplitterPosition(20);
        outer.setSizeFull();
        add(outer);
    }

    // --- left pane: feeds navigation tree (RSSOwl's BookMarkExplorer) ---

    private void configureFeedTree() {
        feedTree.setSizeFull();
        feedTree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);

        // Split into category folders and ungrouped top-level channels (category "Uncategorized"),
        // mirroring RSSOwl's default_feeds.xml: folders + loose channels beside them.
        Map<String, List<NewsItem>> byCat = new TreeMap<>(); // alphabetical -> Business first
        List<NewsItem> ungrouped = new ArrayList<>();
        for (NewsItem n : allItems) {
            if ("Uncategorized".equals(n.category())) ungrouped.add(n);
            else byCat.computeIfAbsent(n.category(), k -> new ArrayList<>()).add(n);
        }
        // Mutable hierarchy so channels can be dragged around (see enableFeedDragAndDrop()).
        feedData = new TreeData<>();
        for (var e : byCat.entrySet()) {
            FeedNode.Category catNode = new FeedNode.Category(e.getKey(), e.getValue().size());
            feedData.addItem(null, catNode);
            Map<String, Long> byFeed = e.getValue().stream()
                    .collect(Collectors.groupingBy(NewsItem::feed, TreeMap::new, Collectors.counting()));
            byFeed.forEach((feed, count) ->
                    feedData.addItem(catNode, new FeedNode.Feed(feed, e.getKey(), count.intValue())));
        }
        // Top-level channels = genuinely ungrouped feeds + a few "featured" big outlets that ALSO
        // live in folders (RSSOwl lists e.g. BBC News both inside a folder and as a loose channel).
        Map<String, Long> topLevel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ungrouped.forEach(n -> topLevel.merge(n.feed(), 1L, Long::sum));
        allItems.stream().filter(n -> FEATURED.contains(n.feed()))
                .forEach(n -> topLevel.merge(n.feed(), 1L, Long::sum));
        topLevel.forEach((feed, count) ->
                feedData.addItem(null, new FeedNode.Feed(feed, "Uncategorized", count.intValue())));

        // Saved-search smart folders at the bottom (RSSOwl: Unread/Today/Attachments/Sticky/Labeled).
        for (String[] s : SAVED) {
            int c = (int) allItems.stream().filter(savedPredicate(s[1])).count();
            feedData.addItem(null, new FeedNode.Saved(s[0], s[1], c));
        }

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
                currentItems = allItems.stream().filter(n -> c.name().equals(n.category())).toList();
            } else if (sel instanceof FeedNode.Feed f) {
                currentItems = allItems.stream().filter(n -> f.name().equals(n.feed())).toList();
            } else if (sel instanceof FeedNode.Saved sv) {
                currentItems = allItems.stream().filter(savedPredicate(sv.key())).toList();
            } else {
                currentItems = allItems;
            }
            applyGrouping(currentGroupBy);
        });
    }

    /**
     * Mouse drag-and-drop reordering of the feeds tree, like RSSOwl's BookMarkExplorer. Only channels
     * (Feed nodes) are draggable; category folders and saved-search folders stay put. Dropping a
     * channel reorders it among its siblings, drops it <em>into</em> a folder (drop on top of one), or
     * pops it back out to the top level — depending on where you release.
     */
    private void enableFeedDragAndDrop() {
        feedTree.setRowsDraggable(true);
        feedTree.setDragFilter(n -> n instanceof FeedNode.Feed);
        // Set the drop mode once, up front, rather than inside dragStart: a per-drag setDropMode
        // only reaches the client after a server round-trip, which can make the first drop after
        // grab flaky. It has no visible effect except while a drag is actually in progress.
        feedTree.setDropMode(GridDropMode.ON_TOP_OR_BETWEEN);

        feedTree.addDragStartListener(e -> {
            FeedNode n = e.getDraggedItems().get(0);
            draggedFeed = (n instanceof FeedNode.Feed f) ? f : null;
        });
        feedTree.addDragEndListener(e -> draggedFeed = null);
        feedTree.addDropListener(e -> {
            FeedNode target = e.getDropTargetItem().orElse(null);
            if (draggedFeed == null || target == null || target.equals(draggedFeed)) {
                return;
            }
            moveFeed(draggedFeed, target, e.getDropLocation());
            feedDataProvider.refreshAll();
            feedTree.select(draggedFeed); // keep the moved channel selected, like RSSOwl
        });
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
    private void placeRelative(FeedNode.Feed dragged, FeedNode parent, FeedNode target, GridDropLocation loc) {
        if (loc != GridDropLocation.ABOVE) {
            feedData.moveAfterSibling(dragged, target);
        } else {
            List<FeedNode> sibs = new ArrayList<>(feedData.getChildren(parent));
            sibs.remove(dragged);
            int idx = sibs.indexOf(target);
            feedData.moveAfterSibling(dragged, idx <= 0 ? null : sibs.get(idx - 1)); // null => first
        }
    }

    private Component buildToolbar(FeedService feedService) {
        Select<GroupBy> groupBy = new Select<>();
        groupBy.setLabel("Group by");
        groupBy.setItems(GroupBy.values());
        groupBy.setItemLabelGenerator(GroupBy::label);
        groupBy.setValue(GroupBy.NONE);
        groupBy.addValueChangeListener(e -> {
            currentGroupBy = e.getValue();
            applyGrouping(currentGroupBy);
        });

        HorizontalLayout bar = new HorizontalLayout(groupBy);
        bar.setAlignItems(FlexComponent.Alignment.END);
        bar.getStyle().set("padding", "0.4rem 1rem");
        if (feedService.isFallback()) {
            Span banner = new Span("⚠ Live feeds unreachable — showing bundled demo data.");
            banner.getStyle().set("color", "#b00").set("align-self", "center");
            bar.add(banner);
        }
        return bar;
    }

    // --- top-right pane: headlines ---

    private void configureHeadlines() {
        headlines.setSizeFull();
        headlines.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        headlines.setSelectionMode(TreeGrid.SelectionMode.SINGLE);

        headlines.addComponentColumn(row -> itemOnly(row, this::stateIcon))
                .setHeader("").setKey("status").setWidth("46px").setFlexGrow(0)
                .setComparator(rowCmp(Comparator.comparingInt(NewsItem::statusRank))).setSortable(true);

        headlines.addComponentHierarchyColumn(this::titleComponent)
                .setHeader("Title").setKey("title").setFlexGrow(3)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::title, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        headlines.addColumn(row -> row instanceof Row.ItemRow ir ? ir.news().author() : "")
                .setHeader("Author").setKey("author").setFlexGrow(1)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::author, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        headlines.addColumn(row -> row instanceof Row.ItemRow ir ? ir.news().feed() : "")
                .setHeader("Feed").setKey("feed").setFlexGrow(1)
                .setComparator(rowCmp(Comparator.comparing(NewsItem::feed, String.CASE_INSENSITIVE_ORDER)))
                .setSortable(true);

        dateColumn = headlines.addColumn(row -> {
                    if (row instanceof Row.ItemRow ir && ir.news().date() != null) {
                        return DATE_FMT.format(ir.news().date());
                    }
                    return "";
                })
                .setHeader("Date").setKey("date").setWidth("160px").setFlexGrow(0)
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

        headlines.addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(row -> {
            if (row instanceof Row.ItemRow ir) selected.set(ir.news());
        }));
        headlines.addItemDoubleClickListener(e -> {
            if (e.getItem() instanceof Row.ItemRow ir) openLink(ir.news());
        });

        buildContextMenu();
    }

    private void applyGrouping(GroupBy by) {
        children.clear();
        List<Row> roots = new ArrayList<>();
        if (by == GroupBy.NONE) {
            for (NewsItem n : currentItems) roots.add(new Row.ItemRow(n));
            headlines.setItems(roots, r -> List.of());
        } else {
            for (Bucket b : Grouping.group(currentItems, by)) {
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
        });
        return reader;
    }

    private void buildContextMenu() {
        GridContextMenu<Row> menu = headlines.addContextMenu();
        menu.addItem("Open original", e -> itemOf(e.getItem()).ifPresent(this::openLink));
        menu.addSeparator();
        GridMenuItem<Row> read = menu.addItem("Mark read", e -> e.getItem().ifPresent(this::toggleReadAndRefresh));
        GridMenuItem<Row> sticky = menu.addItem("Make sticky", e -> e.getItem().ifPresent(this::toggleStickyAndRefresh));
        menu.setDynamicContentHandler(row -> {
            if (!(row instanceof Row.ItemRow ir)) return false; // no menu on group rows
            read.setText(ir.news().unread() ? "Mark read" : "Mark unread");
            sticky.setText(ir.news().sticky() ? "Remove sticky" : "Make sticky");
            return true;
        });
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

    private void toggleReadAndRefresh(Row row) {
        if (row instanceof Row.ItemRow ir) {
            ir.news().toggleRead();
            headlines.getDataProvider().refreshItem(ir);
        }
    }

    private void toggleStickyAndRefresh(Row row) {
        if (row instanceof Row.ItemRow ir) {
            ir.news().toggleSticky();
            headlines.getDataProvider().refreshItem(ir);
        }
    }

    private void openLink(NewsItem it) {
        if (it != null) UI.getCurrent().getPage().open(it.link(), "_blank");
    }

    private Optional<NewsItem> itemOf(Optional<Row> row) {
        return row.filter(r -> r instanceof Row.ItemRow).map(r -> ((Row.ItemRow) r).news());
    }

    /** Predicate behind a saved-search smart folder. "labeled" is a stub (the PoC has no user-label
     *  feature — the colours are per-category, not user labels), so it stays empty, honestly. */
    private java.util.function.Predicate<NewsItem> savedPredicate(String key) {
        return switch (key) {
            case "unread" -> NewsItem::unread;
            case "today" -> n -> n.date() != null
                    && n.date().toLocalDate().equals(java.time.LocalDate.now());
            case "attachments" -> NewsItem::attachments;
            case "sticky" -> NewsItem::sticky;
            default -> n -> false; // "labeled" — no user labels in the PoC
        };
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
