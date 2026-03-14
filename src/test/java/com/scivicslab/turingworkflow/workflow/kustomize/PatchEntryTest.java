/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.workflow.kustomize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PatchEntry.
 *
 * @author devteam@scivicslab.com
 * @since 2.9.1
 */
class PatchEntryTest {

    @Test
    @DisplayName("Should create PatchEntry with only patch file")
    void testCreateWithPatchOnly() {
        PatchEntry entry = new PatchEntry("patch.yaml");

        assertEquals("patch.yaml", entry.getPatch());
        assertNull(entry.getTarget());
        assertFalse(entry.hasTarget());
    }

    @Test
    @DisplayName("Should create PatchEntry with target and patch")
    void testCreateWithTargetAndPatch() {
        PatchEntry entry = new PatchEntry("setup.yaml", "patch-setup.yaml");

        assertEquals("setup.yaml", entry.getTarget());
        assertEquals("patch-setup.yaml", entry.getPatch());
        assertTrue(entry.hasTarget());
    }

    @Test
    @DisplayName("Should create PatchEntry with default constructor")
    void testDefaultConstructor() {
        PatchEntry entry = new PatchEntry();

        assertNull(entry.getTarget());
        assertNull(entry.getPatch());
        assertFalse(entry.hasTarget());
    }

    @Test
    @DisplayName("Should set target and patch via setters")
    void testSetters() {
        PatchEntry entry = new PatchEntry();
        entry.setTarget("main.yaml");
        entry.setPatch("patch-main.yaml");

        assertEquals("main.yaml", entry.getTarget());
        assertEquals("patch-main.yaml", entry.getPatch());
        assertTrue(entry.hasTarget());
    }

    @Test
    @DisplayName("hasTarget should return false for empty target")
    void testHasTargetWithEmptyString() {
        PatchEntry entry = new PatchEntry();
        entry.setTarget("");
        entry.setPatch("patch.yaml");

        assertFalse(entry.hasTarget());
    }

    @Test
    @DisplayName("toString should show patch only when no target")
    void testToStringWithoutTarget() {
        PatchEntry entry = new PatchEntry("patch.yaml");
        String str = entry.toString();

        assertTrue(str.contains("patch='patch.yaml'"));
        assertFalse(str.contains("target="));
    }

    @Test
    @DisplayName("toString should show target and patch when target is set")
    void testToStringWithTarget() {
        PatchEntry entry = new PatchEntry("setup.yaml", "patch-setup.yaml");
        String str = entry.toString();

        assertTrue(str.contains("target='setup.yaml'"));
        assertTrue(str.contains("patch='patch-setup.yaml'"));
    }
}
