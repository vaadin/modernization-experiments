/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines.data;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsBinRepository extends JpaRepository<NewsBin, Long> {
    List<NewsBin> findByOwnerOrderByPositionAsc(String owner);
}
