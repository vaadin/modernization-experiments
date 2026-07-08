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

import org.h2.fulltext.FullTextLucene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene-backed full-text search over the {@code ARTICLE} table, via H2's {@link FullTextLucene}.
 * RSSOwl bundled Lucene for the same purpose; here H2 maintains a Lucene index over the article
 * title + body + author and answers queries with Lucene syntax (phrases, boolean, field-scoped,
 * relevance ranking) — a real index, not the substring scan the toolbar used before.
 *
 * <p>The index is (re)built once at startup, before the feed refresh inserts articles, so the
 * full-text trigger keeps it current as new articles arrive. Results are NOT owner-scoped here;
 * {@link UserNewsService#search} filters them to what the calling user may see.
 */
@Service
public class ArticleSearchService implements ArticleSearch {

    private static final Logger log = LoggerFactory.getLogger(ArticleSearchService.class);

    private final DataSource dataSource;

    public ArticleSearchService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** (Re)build the Lucene index over ARTICLE(title, content, author). Idempotent — safe to call on
     *  every startup; it drops and recreates so a re-run can't double-index. */
    public void ensureIndex() {
        try (Connection pooled = dataSource.getConnection()) {
            Connection c = h2(pooled); // FullTextLucene casts to org.h2.jdbc.JdbcConnection — unwrap the pool proxy
            FullTextLucene.init(c);
            try {
                FullTextLucene.dropIndex(c, "PUBLIC", "ARTICLE");
            } catch (SQLException noExistingIndex) {
                // first run for this database — nothing to drop
            }
            // Index the plain-text projection (CONTENT_TEXT — note the snake_case column name from the
            // JPA naming strategy), not raw CONTENT: raw HTML can contain a single token over Lucene's
            // 32,766-byte max term length, which would fail the whole document.
            FullTextLucene.createIndex(c, "PUBLIC", "ARTICLE", "TITLE,CONTENT_TEXT,AUTHOR");
            log.info("Full-text index ready over ARTICLE(title, content_text, author).");
        } catch (SQLException e) {
            log.warn("Could not initialize the full-text index — search will return nothing: {}", e.toString());
        }
    }

    /** H2's FullTextLucene internally casts the JDBC connection to {@code org.h2.jdbc.JdbcConnection};
     *  Spring hands out a Hikari pool proxy, so unwrap to the underlying H2 connection first. */
    private static Connection h2(Connection pooled) throws SQLException {
        return pooled.unwrap(org.h2.jdbc.JdbcConnection.class);
    }

    @Override
    public List<Long> searchIds(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        try (Connection pooled = dataSource.getConnection();
                ResultSet rs = FullTextLucene.searchData(h2(pooled), query, limit, 0)) {
            while (rs.next()) {
                // searchData rows expose the matched row's primary-key value(s) in the KEYS array.
                // H2 2.4.x returns that as a java.sql.Array (not a bare Object[]), so unwrap both forms.
                Long id = firstKeyAsLong(rs.getObject("KEYS"));
                if (id != null) ids.add(id);
            }
        } catch (SQLException e) {
            // Usually an unparseable Lucene query (stray quote, etc.) — treat as no matches.
            log.debug("Full-text query failed for \"{}\": {}", query, e.toString());
        }
        return ids;
    }

    /** Extract the first primary-key value from a FullText {@code KEYS} cell, which H2 may hand back
     *  either as an {@code Object[]} or wrapped in a {@link java.sql.Array}. */
    private static Long firstKeyAsLong(Object keysObj) {
        Object[] keys = null;
        if (keysObj instanceof Object[] arr) {
            keys = arr;
        } else if (keysObj instanceof java.sql.Array sqlArray) {
            try {
                if (sqlArray.getArray() instanceof Object[] arr) keys = arr;
            } catch (SQLException e) {
                return null;
            }
        }
        if (keys == null || keys.length == 0 || keys[0] == null) return null;
        if (keys[0] instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(keys[0].toString());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
