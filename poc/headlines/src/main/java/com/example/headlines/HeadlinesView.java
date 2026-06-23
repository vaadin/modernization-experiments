package com.example.headlines;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.signals.Signal;
import com.vaadin.flow.signals.local.ValueSignal;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * POC: RSSOwl's headline table ({@code NewsTableControl}/{@code NewsTableLabelProvider}/
 * {@code NewsComparator}) migrated to a Vaadin 25.1 {@link Grid}, with the article reader wired
 * via a {@link ValueSignal} (Vaadin Signals), inside a {@link SplitLayout}.
 *
 * <p>Scope note (honest): the production design targets {@code TreeGrid} so rows can be grouped;
 * this POC uses a flat {@code Grid} — which is exactly the headline question ("can the SWT table
 * move to a Vaadin Grid"). Grouping/TreeGrid is documented in the report as the next step.
 */
@Route("")
@PageTitle("Headlines — SWT→Vaadin POC")
public class HeadlinesView extends Div {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<NewsItem> items = new ArrayList<>(DemoData.sample());
    private final Grid<NewsItem> grid = new Grid<>();

    /** Single source of truth for the selected headline — the detail pane reacts to this. */
    private final ValueSignal<NewsItem> selected = new ValueSignal<NewsItem>((NewsItem) null);

    public HeadlinesView() {
        setSizeFull();
        addClassName("headlines-view");

        configureGrid();

        Div detail = buildReactiveDetail();

        SplitLayout split = new SplitLayout(grid, detail);
        split.setSizeFull();
        split.setSplitterPosition(62);
        add(split);
    }

    private void configureGrid() {
        grid.setSizeFull();
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.setItems(items);

        // Status icon (new/updated/unread/read) — RSSOwl's TITLE-column state icon.
        grid.addComponentColumn(this::stateIcon)
                .setHeader("").setKey("status").setWidth("46px").setFlexGrow(0)
                .setComparator(Comparator.comparingInt(NewsItem::statusRank)).setSortable(true);

        // Title — label colour applied as text colour (RSSOwl's label foreground); bold-unread
        // is handled by the row part-name generator + CSS, not here.
        grid.addComponentColumn(this::titleCell)
                .setHeader("Title").setKey("title").setFlexGrow(3)
                .setComparator(Comparator.comparing(NewsItem::title, String.CASE_INSENSITIVE_ORDER))
                .setSortable(true);

        grid.addColumn(NewsItem::author).setHeader("Author").setKey("author").setFlexGrow(1)
                .setComparator(Comparator.comparing(NewsItem::author, String.CASE_INSENSITIVE_ORDER))
                .setSortable(true);

        grid.addColumn(NewsItem::category).setHeader("Category").setKey("category").setFlexGrow(1)
                .setComparator(Comparator.comparing(NewsItem::category, String.CASE_INSENSITIVE_ORDER))
                .setSortable(true);

        grid.addColumn(it -> it.date() == null ? "" : DATE_FMT.format(it.date()))
                .setHeader("Date").setKey("date").setWidth("160px").setFlexGrow(0)
                // null dates sort last, mirroring RSSOwl's NewsComparator.
                .setComparator(Comparator.comparing(NewsItem::date,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .setSortable(true);

        // In-cell interactive icons (replacing RSSOwl's pixel hit-testing in onMouseDown).
        grid.addComponentColumn(this::readToggle)
                .setHeader("").setKey("read").setWidth("46px").setFlexGrow(0);
        grid.addComponentColumn(this::stickyToggle)
                .setHeader("").setKey("sticky").setWidth("46px").setFlexGrow(0);

        // Owner-draw equivalents: bold unread + sticky row background, via part names + CSS.
        grid.setPartNameGenerator(it -> {
            StringBuilder sb = new StringBuilder();
            if (it.unread()) sb.append("unread");
            if (it.sticky()) sb.append(sb.isEmpty() ? "" : " ").append("sticky");
            return sb.isEmpty() ? null : sb.toString();
        });

        // Default sort: newest first (RSSOwl's default fallback is by date, descending).
        grid.sort(com.vaadin.flow.component.grid.GridSortOrder
                .desc(grid.getColumnByKey("date")).build());

        // Selection -> detail: publish the selection into the signal (no listener plumbing on
        // the detail side; it reacts in buildReactiveDetail()).
        grid.addSelectionListener(e -> selected.set(e.getFirstSelectedItem().orElse(null)));

        // Double-click opens the original article (RSSOwl: OpenInBrowserAction).
        // Note: the item-click event exposes getItem() as a plain T (the context-menu event uses
        // Optional<T>) — a small Grid API inconsistency worth recording.
        grid.addItemDoubleClickListener(e -> openLink(e.getItem()));

        buildContextMenu();
    }

    private Div buildReactiveDetail() {
        Div detail = new Div();
        detail.setSizeFull();
        detail.getStyle().set("padding", "1rem").set("overflow", "auto");

        // The effect re-runs whenever `selected` changes — this is the Signals replacement for
        // RSSOwl's workbench selection-service wiring into NewsBrowserControl.
        Signal.effect(detail, () -> {
            NewsItem it = selected.get();
            detail.removeAll();
            if (it == null) {
                Span hint = new Span("Select a headline to read it.");
                hint.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");
                detail.add(hint);
                return;
            }
            H3 title = new H3(it.title());
            if (it.labelColor() != null) {
                title.getStyle().set("color", it.labelColor());
            }
            Paragraph meta = new Paragraph(
                    (it.date() == null ? "—" : DATE_FMT.format(it.date()))
                            + "  ·  " + it.author() + "  ·  " + it.category()
                            + "  ·  " + it.state());
            meta.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");
            Anchor link = new Anchor(it.link(), "Open original ↗");
            link.setTarget("_blank");
            detail.add(title, meta, link);
        });
        return detail;
    }

    private void buildContextMenu() {
        GridContextMenu<NewsItem> menu = grid.addContextMenu();
        menu.addItem("Open original", e -> e.getItem().ifPresent(this::openLink));
        menu.addSeparator();
        GridMenuItem<NewsItem> read = menu.addItem("Mark read", e ->
                e.getItem().ifPresent(this::toggleReadAndRefresh));
        GridMenuItem<NewsItem> sticky = menu.addItem("Make sticky", e ->
                e.getItem().ifPresent(this::toggleStickyAndRefresh));

        // Rebuild per open — the RSSOwl MenuManager.setRemoveAllWhenShown + menuAboutToShow shape.
        menu.setDynamicContentHandler(item -> {
            if (item == null) return false; // suppress on empty area / header
            read.setText(item.unread() ? "Mark read" : "Mark unread");
            sticky.setText(item.sticky() ? "Remove sticky" : "Make sticky");
            return true;
        });
    }

    // --- cell components ---

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

    private Span titleCell(NewsItem it) {
        Span s = new Span(it.title());
        if (it.labelColor() != null) {
            s.getStyle().set("color", it.labelColor());
        }
        return s;
    }

    private Button readToggle(NewsItem it) {
        Button b = new Button(new Icon(it.unread() ? VaadinIcon.ENVELOPE : VaadinIcon.ENVELOPE_OPEN));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        b.getElement().setAttribute("title", it.unread() ? "Mark read" : "Mark unread");
        b.addClickListener(e -> toggleReadAndRefresh(it));
        return b;
    }

    private Button stickyToggle(NewsItem it) {
        Button b = new Button(new Icon(VaadinIcon.PIN));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        b.getElement().getStyle().set("color", it.sticky() ? "#c9a200" : "var(--vaadin-text-color-secondary, #bbb)");
        b.getElement().setAttribute("title", it.sticky() ? "Remove sticky" : "Make sticky");
        b.addClickListener(e -> toggleStickyAndRefresh(it));
        return b;
    }

    // --- actions ---

    private void toggleReadAndRefresh(NewsItem it) {
        it.toggleRead();
        grid.getDataProvider().refreshItem(it);
    }

    private void toggleStickyAndRefresh(NewsItem it) {
        it.toggleSticky();
        grid.getDataProvider().refreshItem(it);
    }

    private void openLink(NewsItem it) {
        if (it != null) {
            UI.getCurrent().getPage().open(it.link(), "_blank");
        }
    }
}
