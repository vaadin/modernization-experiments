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

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA converter that encrypts a stored value at rest with AES-256-GCM, so per-feed passwords
 * ({@link Subscription#getAuthPassword()}) are never persisted in clear text. The key is derived
 * (SHA-256) from the {@code APP_CREDENTIAL_KEY} environment variable; if unset, a clearly-insecure
 * dev key is used and a warning is logged — a real deployment must set the env var (or use a KMS).
 *
 * <p>Ciphertext is stored as {@code "enc:" + base64(iv || ciphertext)}. Values without the prefix are
 * passed through unchanged, so the converter tolerates any legacy plaintext rows.
 */
@Converter
public class CredentialCipher implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String PREFIX = "enc:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecretKey KEY = deriveKey();
    private static final SecureRandom RNG = new SecureRandom();

    private static SecretKey deriveKey() {
        String secret = System.getenv("APP_CREDENTIAL_KEY");
        if (secret == null || secret.isBlank()) {
            log.warn("APP_CREDENTIAL_KEY not set — using an INSECURE dev key for credential encryption. "
                    + "Set APP_CREDENTIAL_KEY in production.");
            secret = "dev-only-insecure-credential-key";
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive credential key", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(PREFIX)) {
            return dbData; // tolerate pre-existing plaintext
        }
        try {
            byte[] packed = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            ByteBuffer bb = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_BYTES];
            bb.get(iv);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }
}
