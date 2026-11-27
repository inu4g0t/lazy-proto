package io.lazyproto.runtime;

import java.util.AbstractList;
import java.util.RandomAccess;
import java.util.function.IntFunction;

/**
 * Unmodifiable lazy {@link java.util.List} view over a {@code repeated} Protobuf field.
 *
 * <p>Calling {@code get(index)} instantiates only the requested element without deserializing siblings.
 */
public final class ImmutableLazyList<E> extends AbstractList<E> implements RandomAccess {
    private final int size;
    private final IntFunction<E> elementAt;

    public ImmutableLazyList(int size, IntFunction<E> elementAt) {
        this.size = size;
        this.elementAt = elementAt;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public E get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
        }
        return elementAt.apply(index);
    }
}
