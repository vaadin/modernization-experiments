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

import com.example.headlines.service.UserNewsService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Manage the user's labels — RSSOwl's "Manage Labels" dialog: create, rename, recolour, and delete
 * the per-user label set. Changes persist through {@link UserNewsService}; {@code onChanged} lets the
 * headlines view rebuild its label menu and re-resolve item labels afterwards.
 */
public class LabelsDialog extends Dialog {

    private final UserNewsService news;
    private final String subject;
    private final Runnable onChanged;
    private final VerticalLayout list = new VerticalLayout();

    public LabelsDialog(UserNewsService news, String subject, Runnable onChanged) {
        this.news = news;
        this.subject = subject;
        this.onChanged = onChanged;
        setHeaderTitle("Manage labels");
        setWidth("420px");
        list.setPadding(false);
        list.setSpacing(false);
        add(list);

        Button add = new Button("New label", VaadinIcon.PLUS.create(), e -> openEditor(0, "", "#1565c0"));
        add.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        getFooter().add(add, new Button("Close", e -> close()));
        refreshList();
    }

    private void refreshList() {
        list.removeAll();
        for (NewsItem.LabelRef l : news.labels(subject)) {
            Span swatch = new Span();
            swatch.getStyle().set("display", "inline-block").set("width", "14px").set("height", "14px")
                    .set("border-radius", "50%").set("background-color", l.color());
            Span name = new Span(l.name());
            name.getStyle().set("flex-grow", "1");
            Button edit = new Button(VaadinIcon.EDIT.create(), e -> openEditor(l.id(), l.name(), l.color()));
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            Button del = new Button(VaadinIcon.TRASH.create(), e -> {
                news.deleteLabel(subject, l.id());
                refreshList();
                onChanged.run();
            });
            del.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR);
            HorizontalLayout row = new HorizontalLayout(swatch, name, edit, del);
            row.setWidthFull();
            row.setAlignItems(FlexComponent.Alignment.CENTER);
            list.add(row);
        }
    }

    /** Editor for a label; {@code id == 0} means create a new one. */
    private void openEditor(long id, String currentName, String currentColor) {
        Dialog editor = new Dialog();
        editor.setHeaderTitle(id == 0 ? "New label" : "Edit label");

        TextField name = new TextField("Name");
        name.setValue(currentName);
        name.setWidthFull();

        TextField color = new TextField("Colour (hex)");
        color.setValue(currentColor);
        color.setPlaceholder("#1565c0");
        Span preview = new Span();
        preview.getStyle().set("display", "inline-block").set("width", "20px").set("height", "20px")
                .set("border-radius", "50%").set("background-color", currentColor).set("align-self", "center");
        color.addValueChangeListener(e -> preview.getStyle().set("background-color", safeColor(e.getValue())));
        HorizontalLayout colorRow = new HorizontalLayout(color, preview);
        colorRow.setAlignItems(FlexComponent.Alignment.END);
        colorRow.setWidthFull();
        color.getStyle().set("flex-grow", "1");

        editor.add(new VerticalLayout(name, colorRow));

        Button save = new Button("Save", e -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true); name.setErrorMessage("A name is required"); return;
            }
            String c = safeColor(color.getValue());
            if (id == 0) news.createLabel(subject, name.getValue().trim(), c);
            else news.updateLabel(subject, id, name.getValue().trim(), c);
            editor.close();
            refreshList();
            onChanged.run();
            Notification.show(id == 0 ? "Label created" : "Label updated");
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        editor.getFooter().add(new Button("Cancel", e -> editor.close()), save);
        editor.open();
    }

    /** Accept a #rgb/#rrggbb hex; fall back to a neutral grey if blank/invalid. */
    private static String safeColor(String c) {
        if (c != null && c.matches("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})")) return c;
        return "#888888";
    }
}
