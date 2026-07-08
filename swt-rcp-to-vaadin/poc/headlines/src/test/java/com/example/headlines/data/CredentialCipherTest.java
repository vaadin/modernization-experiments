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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link CredentialCipher} — per-feed passwords are encrypted at rest, never plaintext. */
class CredentialCipherTest {

    private final CredentialCipher cipher = new CredentialCipher();

    @Test
    void encryptsAndRoundTrips() {
        String enc = cipher.convertToDatabaseColumn("s3cret-password");
        assertTrue(enc.startsWith("enc:"), "stored value is marked encrypted");
        assertFalse(enc.contains("s3cret-password"), "plaintext must not appear in the stored value");
        assertEquals("s3cret-password", cipher.convertToEntityAttribute(enc), "decrypts back");
    }

    @Test
    void randomIvMakesCiphertextNonDeterministic() {
        assertNotEquals(cipher.convertToDatabaseColumn("same"), cipher.convertToDatabaseColumn("same"));
    }

    @Test
    void nullIsPassedThrough() {
        assertNull(cipher.convertToDatabaseColumn(null));
        assertNull(cipher.convertToEntityAttribute(null));
    }

    @Test
    void legacyPlaintextIsTolerated() {
        assertEquals("not-encrypted", cipher.convertToEntityAttribute("not-encrypted"));
    }
}
