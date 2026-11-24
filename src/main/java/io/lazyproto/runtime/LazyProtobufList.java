package io.lazyproto.runtime;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.RandomAccess;
import java.util.function.Supplier;

/**
 * Lazy {@link java.util.List} implementation backed directly by {@link AbstractLazyMessage}'s
 * intrusive static linked list in {@code FieldTape}.
 *
 * <p>Accessing {@code list.get(k)} only deserializes the {@code k}-th element in the list!
 * All other unvisited elements remain as raw byte slices in the parent message's {@code FieldTape}
 * and are bulk-copied as contiguous raw byte spans during serialization.
 */
public final class LazyProtobufList<E extends AbstractLazyMessage<E>>
        extends AbstractList<E> implements RandomAccess {

    private final AbstractLazyMessage<?> host;
    private final long parentFieldBit;
    private final Supplier<E> elementFactory;

    private int headTapeIdx = AbstractLazyMessage.NO_NEXT_IDX;
    private int tailTapeIdx = AbstractLazyMessage.NO_NEXT_IDX;
    private int count;

    private static final int[] EMPTY_INTS = new int[0];
    private static final Object[] EMPTY_OBJS = new Object[0];
    private static final boolean[] EMPTY_BOOLS = new boolean[0];

    // Lazily populated flat array mapping list index [0..count) -> tapeIdx
    private int[] indexToTapeIdx = EMPTY_INTS;
    private boolean indexed;

    // Reusable pool of element view instances (only allocated up to accessed indices)
    private Object[] elementViews = EMPTY_OBJS;
    private boolean[] elementLoaded = EMPTY_BOOLS;

    // Newly appended elements (added via addElement() after deserialization)
    private Object[] appendedElements;
    private int appendedCount;

    public LazyProtobufList(AbstractLazyMessage<?> host, long parentFieldBit, Supplier<E> elementFactory) {
        this.host = host;
        this.parentFieldBit = parentFieldBit;
        this.elementFactory = elementFactory;
    }

    public void reset() {
        for (int i = 0; i < count && i < elementViews.length; i++) {
            if (elementLoaded[i] && elementViews[i] != null) {
                @SuppressWarnings("unchecked")
                E view = (E) elementViews[i];
                view.reset();
                elementLoaded[i] = false;
            }
        }
        this.headTapeIdx = AbstractLazyMessage.NO_NEXT_IDX;
        this.tailTapeIdx = AbstractLazyMessage.NO_NEXT_IDX;
        this.count = 0;
        this.indexed = false;
        this.appendedCount = 0;
    }

    /**
     * Shallow-copies the linked list head/tail/count from {@code src} without deserializing unvisited elements.
     * Any already-mutated elements in {@code src} are shallow-cloned so mutations on the new builder remain isolated.
     */
    @SuppressWarnings("unchecked")
    public void copyFrom(LazyProtobufList<E> src, java.util.function.Function<E, E> elementCopier) {
        this.headTapeIdx = src.headTapeIdx;
        this.tailTapeIdx = src.tailTapeIdx;
        this.count = src.count;
        this.indexed = false;
        this.appendedCount = src.appendedCount;
        if (src.appendedCount > 0) {
            this.appendedElements = new Object[src.appendedElements.length];
            for (int i = 0; i < src.appendedCount; i++) {
                this.appendedElements[i] = elementCopier.apply((E) src.appendedElements[i]);
            }
        }
        // Only clone element views that were actually mutated in src!
        if (src.indexed) {
            for (int i = 0; i < src.count; i++) {
                if (src.elementLoaded[i] && src.elementViews[i] != null) {
                    E srcElem = (E) src.elementViews[i];
                    if (!srcElem.isClean()) {
                        ensureIndexBuilt();
                        E clonedElem = elementCopier.apply(srcElem);
                        clonedElem.attachToParent(host, indexToTapeIdx[i], parentFieldBit);
                        this.elementViews[i] = clonedElem;
                        this.elementLoaded[i] = true;
                    }
                }
            }
        }
    }

    /**
     * Called by the parent's {@code onScanField()} in O(1) time to append a wire entry to this repeated field's chain.
     */
    public void linkScannedEntry(int tapeIdx) {
        if (headTapeIdx == AbstractLazyMessage.NO_NEXT_IDX) {
            headTapeIdx = tapeIdx;
            tailTapeIdx = tapeIdx;
        } else {
            host.setTapeNextIdx(tailTapeIdx, tapeIdx);
            tailTapeIdx = tapeIdx;
        }
        count++;
    }

    private void ensureIndexBuilt() {
        if (indexed) return;
        indexed = true;
        if (count > indexToTapeIdx.length) {
            int newCap = Math.max(count, indexToTapeIdx.length << 1);
            indexToTapeIdx = new int[newCap];
            elementViews = Arrays.copyOf(elementViews, newCap);
            elementLoaded = new boolean[newCap];
        }
        int curr = headTapeIdx;
        int i = 0;
        while (curr != AbstractLazyMessage.NO_NEXT_IDX && i < count) {
            indexToTapeIdx[i++] = curr;
            curr = host.tapeNextIdx(curr);
        }
    }

    @Override
    public int size() {
        host.ensureScanned();
        return count + appendedCount;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        host.ensureScanned();
        int total = count + appendedCount;
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + total);
        }
        if (index >= count) {
            return (E) appendedElements[index - count];
        }
        ensureIndexBuilt();
        if (!elementLoaded[index]) {
            E view = (E) elementViews[index];
            if (view == null) {
                view = elementFactory.get();
                elementViews[index] = view;
            }
            int tapeIdx = indexToTapeIdx[index];
            int valOff = host.tapeValueOffset(tapeIdx);
            int valLen = host.tapeValueLength(tapeIdx);
            view.wrap(host.rawBuffer(), valOff, valLen);
            view.attachToParent(host, tapeIdx, parentFieldBit);
            elementLoaded[index] = true;
            return view;
        }
        return (E) elementViews[index];
    }

    /**
     * Looks up a loaded element by its {@code tapeIdx} when serializing a dirty element.
     */
    @SuppressWarnings("unchecked")
    public E getLoadedElementByTapeIdx(int tapeIdx) {
        ensureIndexBuilt();
        // Fast binary search since tapeIdx is strictly increasing!
        int idx = Arrays.binarySearch(indexToTapeIdx, 0, count, tapeIdx);
        if (idx < 0 || !elementLoaded[idx]) {
            throw new IllegalStateException("Dirty tapeIdx " + tapeIdx + " was not loaded");
        }
        return (E) elementViews[idx];
    }

    /**
     * Applies in-place patches to any element that has same-size dirty mutations.
     */
    @SuppressWarnings("unchecked")
    public void applyInPlacePatches(byte[] target, int baseOffset) {
        if (!indexed) return;
        for (int i = 0; i < count; i++) {
            if (elementLoaded[i]) {
                int tapeIdx = indexToTapeIdx[i];
                if (host.tapeFlags(tapeIdx) == AbstractLazyMessage.FLAG_DIRTY_SAME_SIZE) {
                    E view = (E) elementViews[i];
                    view.applyInPlacePatches(target, baseOffset);
                }
            }
        }
    }

    public void addElement(E element) {
        host.ensureScanned();
        if (appendedElements == null) {
            appendedElements = new Object[4];
        } else if (appendedCount >= appendedElements.length) {
            appendedElements = Arrays.copyOf(appendedElements, appendedElements.length << 1);
        }
        appendedElements[appendedCount++] = element;
    }

    public int appendedCount() {
        return appendedCount;
    }

    @SuppressWarnings("unchecked")
    public E getAppended(int idx) {
        return (E) appendedElements[idx];
    }
}
