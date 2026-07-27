/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

/**
 * Tombstone retained only so old source references cannot silently resurrect the removed
 * fallback. HUD Lab 0.21 has no background writer, service or boot receiver.
 */
@Deprecated
final class HudModeFallbackStore {
    private HudModeFallbackStore() {
    }
}
