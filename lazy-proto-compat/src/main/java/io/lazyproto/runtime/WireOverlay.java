package io.lazyproto.runtime;

/**
 * Base class for external Copy-on-Write write overlays attached to an {@link ImmutableWireBase}.
 *
 * <p>All mutations live exclusively inside {@code WireOverlay}; the underlying {@code ImmutableWireBase}
 * is never modified. Calling {@link #freeze()} marks this overlay and any active dirty child overlays
 * as immutable so subsequent writes trigger Copy-on-Write without affecting built snapshots.
 */
public abstract class WireOverlay {
    public boolean frozen;
    public long sameSizeMask;
    public long varSizeMask;
    public long deletedMask;
    public long appendedMask;
    public int cachedSerializedSize = -1;

    public final boolean isFrozen() {
        return frozen;
    }

    public final void freeze() {
        if (!frozen) {
            this.frozen = true;
            onFreezeChildren();
        }
    }

    protected void onFreezeChildren() {}

    public final void invalidateSizeCache() {
        this.cachedSerializedSize = -1;
    }

    public boolean isEmpty() {
        return (sameSizeMask | varSizeMask | deletedMask | appendedMask) == 0L;
    }

    public boolean isSameSizeDirtyOnly(ImmutableWireBase base) {
        return !base.isMultiSegment()
                && !base.hasShadowedEntries()
                && sameSizeMask != 0L
                && (varSizeMask | deletedMask | appendedMask) == 0L;
    }

    public final long sameSizeMask() { return sameSizeMask; }
    public final long varSizeMask() { return varSizeMask; }
    public final long deletedMask() { return deletedMask; }
    public final long appendedMask() { return appendedMask; }

    protected final void assertUnfrozen() {
        if (frozen) {
            throw new IllegalStateException("Cannot mutate a frozen WireOverlay snapshot");
        }
    }

    protected final void resetMasksAndCache() {
        assertUnfrozen();
        this.sameSizeMask = 0L;
        this.varSizeMask = 0L;
        this.deletedMask = 0L;
        this.appendedMask = 0L;
        this.cachedSerializedSize = -1;
    }

    protected final void copyMasksFrom(WireOverlay src) {
        this.sameSizeMask = src.sameSizeMask;
        this.varSizeMask = src.varSizeMask;
        this.deletedMask = src.deletedMask;
        this.appendedMask = src.appendedMask;
        this.cachedSerializedSize = src.cachedSerializedSize;
    }
}
