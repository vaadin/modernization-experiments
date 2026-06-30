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

import com.example.headlines.data.NewsFilter;
import com.example.headlines.data.NewsFilter.Condition;
import com.example.headlines.data.NewsFilter.Field;
import com.example.headlines.data.NewsFilter.MatchMode;
import com.example.headlines.service.UserNewsService;
import com.example.headlines.service.UserNewsService.FilterDef;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;

import java.util.ArrayList;
import java.util.List;

/**
 * News-filter management — RSSOwl's Filters dialog. Lists the user's {@link NewsFilter}s, lets them
 * add/edit/delete (each: a name, ALL/ANY conditions of {@code field contains value}, and additive
 * actions), toggle enabled, and "Apply now". Persists via {@link UserNewsService}; {@code onChanged}
 * refreshes the headlines view after an apply so newly-actioned items update.
 */
public class FiltersDialog extends Dialog {

    /** RSSOwl's default labels (name → colour); reused for the "assign label" action. */
    private static final String[][] LABELS = {
            {"Important", "#c62828"}, {"Work", "#1565c0"}, {"Personal", "#2e7d32"},
            {"To Do", "#ef6c00"}, {"Later", "#6a1b9a"}};

    private final UserNewsService news;
    private final String subject;
    private final Runnable onChanged;
    private final VerticalLayout list = new VerticalLayout();

    public FiltersDialog(UserNewsService news, String subject, Runnable onChanged) {
        this.news = news;
        this.subject = subject;
        this.onChanged = onChanged;
        setHeaderTitle("News filters");
        setWidth("520px");
        list.setPadding(false);
        list.setSpacing(false);
        add(list);

        Button add = new Button("New filter", VaadinIcon.PLUS.create(), e -> openEditor(null));
        add.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button applyNow = new Button("Apply now", e -> {
            int n = news.applyFilters(subject);
            onChanged.run();
            Notification.show(n == 0 ? "Filters applied — nothing to change" : "Filters applied — " + n + " change(s)");
        });
        applyNow.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(add, new Button("Close", e -> close()), applyNow);

        refreshList();
    }

    private void refreshList() {
        list.removeAll();
        List<FilterDef> filters = news.filters(subject);
        if (filters.isEmpty()) {
            Span empty = new Span("No filters yet. \"New filter\" creates one (e.g. Title contains \"linux\" → Mark read).");
            empty.getStyle().set("color", "var(--vaadin-text-color-secondary, gray)");
            list.add(empty);
            return;
        }
        for (FilterDef f : filters) {
            Checkbox enabled = new Checkbox(f.enabled());
            enabled.addValueChangeListener(e -> {
                news.saveFilter(subject, new FilterDef(f.id(), f.name(), e.getValue(), f.matchMode(),
                        f.conditions(), f.actions()));
            });
            Span label = new Span(f.name() + "  —  " + summary(f));
            label.getStyle().set("flex-grow", "1");
            Button edit = new Button(VaadinIcon.EDIT.create(), e -> openEditor(f));
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            Button del = new Button(VaadinIcon.TRASH.create(), e -> { news.deleteFilter(subject, f.id()); refreshList(); });
            del.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR);
            HorizontalLayout row = new HorizontalLayout(enabled, label, edit, del);
            row.setWidthFull();
            row.setAlignItems(HorizontalLayout.Alignment.CENTER);
            list.add(row);
        }
    }

    private static String summary(FilterDef f) {
        String conds = f.conditions().stream()
                .map(c -> c.getField().name().toLowerCase() + " contains \"" + c.getValue() + "\"")
                .reduce((a, b) -> a + (f.matchMode() == MatchMode.ALL ? " AND " : " OR ") + b).orElse("(no conditions)");
        return conds + " → " + (f.actions().isEmpty() ? "(no actions)" : String.join(", ", actionLabels(f.actions())));
    }

    private static List<String> actionLabels(List<String> actions) {
        List<String> out = new ArrayList<>();
        for (String a : actions) {
            if ("MARK_READ".equals(a)) out.add("mark read");
            else if ("MARK_STICKY".equals(a)) out.add("make sticky");
            else if (a.startsWith("LABEL:")) out.add("label " + labelName(a.substring("LABEL:".length())));
        }
        return out;
    }

    private static String labelName(String color) {
        for (String[] l : LABELS) if (l[1].equals(color)) return l[0];
        return color;
    }

    /** Open the add/edit editor for {@code existing} (null = new filter). */
    private void openEditor(FilterDef existing) {
        Dialog editor = new Dialog();
        editor.setHeaderTitle(existing == null ? "New filter" : "Edit filter");
        editor.setWidth("480px");

        TextField name = new TextField("Name");
        name.setWidthFull();
        name.setValue(existing != null ? existing.name() : "");

        Select<MatchMode> mode = new Select<>();
        mode.setLabel("Match");
        mode.setItems(MatchMode.values());
        mode.setItemLabelGenerator(m -> m == MatchMode.ALL ? "All conditions (AND)" : "Any condition (OR)");
        mode.setValue(existing != null ? existing.matchMode() : MatchMode.ALL);

        // Condition rows (start from existing, else one blank row).
        VerticalLayout condRows = new VerticalLayout();
        condRows.setPadding(false);
        condRows.setSpacing(false);
        List<Condition> seed = existing != null && !existing.conditions().isEmpty()
                ? existing.conditions() : List.of(new Condition(Field.TITLE, ""));
        for (Condition c : seed) condRows.add(conditionRow(c));
        Button addCond = new Button("Add condition", VaadinIcon.PLUS.create(),
                e -> condRows.add(conditionRow(new Condition(Field.TITLE, ""))));
        addCond.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);

        // Actions
        Checkbox markRead = new Checkbox("Mark read");
        Checkbox markSticky = new Checkbox("Make sticky");
        Select<String> label = new Select<>();
        label.setLabel("Assign label");
        List<String> labelNames = new ArrayList<>();
        labelNames.add("(none)");
        for (String[] l : LABELS) labelNames.add(l[0]);
        label.setItems(labelNames);
        label.setValue("(none)");
        if (existing != null) {
            markRead.setValue(existing.actions().contains("MARK_READ"));
            markSticky.setValue(existing.actions().contains("MARK_STICKY"));
            existing.actions().stream().filter(a -> a.startsWith("LABEL:")).findFirst()
                    .ifPresent(a -> label.setValue(labelName(a.substring("LABEL:".length()))));
        }

        Span actionsHdr = new Span("Then:");
        actionsHdr.getStyle().set("font-weight", "600");
        editor.add(new VerticalLayout(name, mode, new Span("Where:"), condRows, addCond,
                actionsHdr, markRead, markSticky, label));

        Button save = new Button("Save", e -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true); name.setErrorMessage("A name is required"); return;
            }
            List<Condition> conds = new ArrayList<>();
            condRows.getChildren().forEach(comp -> {
                if (comp instanceof ConditionRow cr && !cr.value().isBlank()) {
                    conds.add(new Condition(cr.field(), cr.value()));
                }
            });
            List<String> actions = new ArrayList<>();
            if (markRead.getValue()) actions.add("MARK_READ");
            if (markSticky.getValue()) actions.add("MARK_STICKY");
            if (!"(none)".equals(label.getValue())) {
                for (String[] l : LABELS) if (l[0].equals(label.getValue())) actions.add("LABEL:" + l[1]);
            }
            news.saveFilter(subject, new FilterDef(existing != null ? existing.id() : null,
                    name.getValue().trim(), existing == null || existing.enabled(), mode.getValue(), conds, actions));
            editor.close();
            refreshList();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        editor.getFooter().add(new Button("Cancel", e -> editor.close()), save);
        editor.open();
    }

    private static ConditionRow conditionRow(Condition c) {
        return new ConditionRow(c);
    }

    /** A single editable condition row: field selector + value field (+ remove). */
    private static class ConditionRow extends HorizontalLayout {
        private final Select<Field> field = new Select<>();
        private final TextField value = new TextField();

        ConditionRow(Condition c) {
            field.setItems(Field.values());
            field.setItemLabelGenerator(f -> switch (f) {
                case TITLE -> "Title"; case AUTHOR -> "Author"; case FEED -> "Feed"; case CONTENT -> "Content";
            });
            field.setValue(c.getField());
            field.setWidth("120px");
            value.setPlaceholder("contains…");
            value.setValue(c.getValue() == null ? "" : c.getValue());
            value.getStyle().set("flex-grow", "1");
            Button remove = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> removeFromParent());
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            add(field, value, remove);
            setWidthFull();
            setAlignItems(Alignment.BASELINE);
        }

        Field field() { return field.getValue(); }
        String value() { return value.getValue() == null ? "" : value.getValue().trim(); }
    }
}
