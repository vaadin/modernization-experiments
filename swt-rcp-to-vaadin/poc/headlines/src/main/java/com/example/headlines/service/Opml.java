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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serialises a user's subscriptions to an OPML document — the format every feed reader imports/exports,
 * and the same shape as RSSOwl's {@code default_feeds.xml}. Folders nest from each subscription's folder
 * <em>path</em> ({@code "Computers/Windows"}); sub-folders and feeds are interleaved in subscription order.
 */
public final class Opml {

    private Opml() {}

    public static String write(List<UserNewsService.FeedRef> refs) {
        List<UserNewsService.FeedRef> ordered = new ArrayList<>(refs);
        ordered.sort(Comparator.comparingInt(UserNewsService.FeedRef::position));

        Map<String, List<UserNewsService.FeedRef>> directFeeds = new LinkedHashMap<>(); // path ("" root) -> feeds
        Set<String> catPaths = new LinkedHashSet<>();
        Map<String, Integer> posByPath = new java.util.HashMap<>();
        for (UserNewsService.FeedRef r : ordered) {
            String folder = folderOf(r.folder());
            directFeeds.computeIfAbsent(folder, k -> new ArrayList<>()).add(r);
            String acc = "";
            for (String seg : folder.isEmpty() ? new String[0] : folder.split("/")) {
                acc = acc.isEmpty() ? seg : acc + "/" + seg;
                catPaths.add(acc);
                posByPath.merge(acc, r.position(), Math::min);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n");
        sb.append("  <head><title>Headlines subscriptions</title></head>\n  <body>\n");
        emit("", "    ", directFeeds, catPaths, posByPath, sb);
        sb.append("  </body>\n</opml>\n");
        return sb.toString();
    }

    private static void emit(String parentPath, String indent,
            Map<String, List<UserNewsService.FeedRef>> directFeeds, Set<String> catPaths,
            Map<String, Integer> posByPath, StringBuilder sb) {
        List<Object> children = new ArrayList<>();
        catPaths.stream().filter(p -> isDirectChild(parentPath, p)).forEach(children::add);
        children.addAll(directFeeds.getOrDefault(parentPath, List.of()));
        children.sort(Comparator.comparingInt(c -> c instanceof String cp
                ? posByPath.getOrDefault(cp, Integer.MAX_VALUE)
                : ((UserNewsService.FeedRef) c).position()));

        for (Object child : children) {
            if (child instanceof String cp) {
                sb.append(indent).append("<outline text=\"").append(esc(lastSegment(cp))).append("\">\n");
                emit(cp, indent + "  ", directFeeds, catPaths, posByPath, sb);
                sb.append(indent).append("</outline>\n");
            } else {
                UserNewsService.FeedRef r = (UserNewsService.FeedRef) child;
                sb.append(indent).append("<outline text=\"").append(esc(r.title()))
                        .append("\" type=\"rss\" xmlUrl=\"").append(esc(r.url())).append("\"/>\n");
            }
        }
    }

    private static boolean isDirectChild(String parentPath, String path) {
        if (parentPath.isEmpty()) return !path.contains("/");
        return path.startsWith(parentPath + "/") && path.indexOf('/', parentPath.length() + 1) < 0;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String folderOf(String folder) {
        return (folder == null || folder.isBlank() || "Uncategorized".equals(folder)) ? "" : folder;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
