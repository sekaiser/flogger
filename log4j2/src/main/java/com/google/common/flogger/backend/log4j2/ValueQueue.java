package com.google.common.flogger.backend.log4j2;

import com.google.common.flogger.MetadataKey;
import com.google.common.flogger.context.Tags;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.google.common.flogger.util.Checks.checkNotNull;

/**
 * A simple FIFO queue linked-list implementation designed to store multiple metadata values
 * in a StringMap. There are two aspects worth pointing out:
 * <p>
 * First, it is expected that a value queue always contains at least a single item.
 * You cannot add null references to the queue and you cannot create an empty queue.
 * </p>
 * <p>
 * Second, it is expected to access the contents of the value queue via an iterator only. Hence we
 * do not provide a method for taking the first item in teh value queue..
 * </p>
 * <p>Metadata values in Flogger always have unique keys, but those keys can have the same label.
 * Because Log4j2 uses a {@code String} keyed map, we would risk clashing of values if we just used
 * the label to store each value directly. This class lets us store a list of values for a single
 * label while being memory efficient in the common case where each label really does only have one
 * value.
 */
final class ValueQueue implements Iterable<Object> {

    private final List<Object> values = new LinkedList<>();

    private ValueQueue() { }

    static ValueQueue newQueue(Object item) {
        checkNotNull(item, "item");
        ValueQueue valueQueue = new ValueQueue();
        valueQueue.put(item);
        return valueQueue;
    }

    static Object maybeWrap(Object value, @NullableDecl Object existingValue) {
        checkNotNull(value, "value");
        if (existingValue == null) {
            return value;
        } else {
            // This should only rarely happen, so a few small allocations seems acceptable.
            ValueQueue existingQueue = existingValue instanceof ValueQueue
                    ? (ValueQueue) existingValue
                    : ValueQueue.newQueue(existingValue);
            existingQueue.put(value);
            return existingQueue;
        }
    }

    static void appendValues(String label, Object valueOrQueue, MetadataKey.KeyValueHandler kvh) {
        if (valueOrQueue instanceof ValueQueue) {
            for (Object value : (ValueQueue) valueOrQueue) {
                emit(label, value, kvh);
            }
        } else {
            emit(label, valueOrQueue, kvh);
        }
    }

    /**
     * Helper method for creating and initializing a value queue with a non-nullable value.
     * If value is an instance of Tags, each tag will be added to teh value queue.
     */
    static ValueQueue appendValueToNewQueue(Object value) {
        ValueQueue valueQueue = new ValueQueue();
        ValueQueue.emit(null, value, (k, v) -> valueQueue.put(v));
        return valueQueue;
    }

    /**
     * Tags are key-value mappings which cannot be modified or replaced. If you add
     * the tag mapping "foo" -> true and later add the mapping "foo" -> false, you
     * get the value "foo" mapped to both true and false. This is *very* deliberate
     * since the key space for tags is global and the risk of two bits of code
     * accidentally using the same tag name is real (e.g. you get "id=abcd" but you
     * added "id=xyz" so you think this isn't your log entry, but someone else added
     * "id=abcd" in a context you weren't aware of).
     *
     * <pre>{@code
     * Given three tag mappings:
     * "baz" -> , "foo" -> true and "foo" -> false
     *
     * the value queue is going to store the mappings as:
     * tags=[baz, foo=false, foo=true]
     * }</pre>
     * <p>
     * Reusing the label 'tags' is intentional as this allows us to store the
     * flatten tags in Log4j2's ContextMap.
     */
    static void emit(String label, Object value, MetadataKey.KeyValueHandler kvh) {
        if (value instanceof Tags) {
            // Flatten tags to treat them as keys or key/value pairs, e.g. tags=[baz=bar, baz=bar2, foo]
            ((Tags) value).asMap().forEach((k, v) -> {
                if (v.isEmpty()) {
                    kvh.handle(label, k);
                } else {
                    for (Object obj : v) {
                        kvh.handle(label, k + "=" + obj.toString());
                    }
                }
            });
        } else {
            kvh.handle(label, value);
        }
    }

    @Override
    public Iterator<Object> iterator() {
        return values.iterator();
    }

    void put(Object item) {
        checkNotNull(item, "item");
        values.add(item);
    }

    int size() {
        return values.size();
    }

    /**
     * Returns a string representation of the contents of the specified value queue.
     * <p>If the value queue is empty, teh method returns an empty string.</p>
     * <p>If the value queue contains a single element {@code a}, this method returns {@code a}.</p>
     * <p>Otherwise, the content of the queue is returned surrounded by brackets "[", "].</p>
     */
    @Override
    public String toString() {
        // This case shouldn't actually happen unless you use the value queue for storing emitted values
        if (values.isEmpty()) {
            return "";
        }

        if (values.size() == 1) {
            return values.get(0).toString();
        }

        return values.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ValueQueue that = (ValueQueue) o;
        return values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }
}