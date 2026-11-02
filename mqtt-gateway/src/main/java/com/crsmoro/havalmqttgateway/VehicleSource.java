package com.crsmoro.havalmqttgateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/** Lifecycle and read-only policy for an explicitly supplied set of OEM properties. */
public final class VehicleSource implements AutoCloseable {
    public enum Status { CONNECTING, CONNECTED, SHIZUKU_UNAVAILABLE, PERMISSION_DENIED, SERVICE_UNAVAILABLE, ERROR, CLOSED }
    public interface Observer {
        void onStatus(Status status);
        void onValue(String key, String value);
        default void onInvalidValue(String key) { }
    }
    public interface Events {
        void onValue(String key, String value);
        /** Record receipt before queuing delivery onto the source worker. */
        Runnable onValueReceived(String key, String value);
        void onDeath();
    }
    public interface Connection extends AutoCloseable {
        void subscribe(List<String> keys) throws Exception;
        String read(String key) throws Exception;
        @Override void close();
    }
    public interface Transport { Connection open(Events events) throws Exception; }
    public interface Cancel { void cancel(); }
    public interface Scheduler { Cancel schedule(long delayMillis, Runnable task); }
    public static final class Failure extends Exception {
        public final Status status;
        public Failure(Status status) { this.status = status; }
    }

    private final List<String> keys;
    private final Set<String> keySet;
    private final Transport transport;
    private final Scheduler scheduler;
    private final Observer observer;

    private Connection connection;
    private Cancel retry;
    private boolean started, closed;
    private long generation;
    private static final long RETRY_MS = 5000;
    private static final String VIN_KEY = "car.basic.vin_code";

    /** The caller is responsible for identifying which OEM properties are actually read-only. */
    public VehicleSource(List<String> keys, Transport transport, Scheduler scheduler, Observer observer) {
        if (keys == null || keys.isEmpty() || transport == null || scheduler == null || observer == null)
            throw new IllegalArgumentException("keys, transport, scheduler and observer are required");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String key : keys) {
            if (!permitsKey(key)) throw new IllegalArgumentException("property is not permitted");
            unique.add(key);
        }
        this.keys = Collections.unmodifiableList(new ArrayList<>(unique));
        this.keySet = Collections.unmodifiableSet(unique);
        this.transport = transport;
        this.scheduler = scheduler;
        this.observer = observer;
    }
    /** Name-based defense in depth, not proof of OEM read-only or vehicle semantics. */
    public static boolean permitsKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.-]{1,160}") || key.endsWith(".") || key.contains("..")) return false;
        if (!key.startsWith("car.")) return false;
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        // Reject common navigation / phone-projection namespaces and terms. This is a
        // defense in depth, not a substitute for the caller's curated vehicle-key list.
        if (normalized.contains("navigation") || normalized.contains("android_auto") || normalized.contains("carplay") ||
            normalized.matches(".*(^|[._-])(nav|map|gps|latitude|longitude|location)([._-]|$).*")) return false;
        if (normalized.startsWith("car.command_parking_setting.") || normalized.equals("car.beantechs.sota_start") ||
            normalized.equals("car.configure.catapult_start") || normalized.equals("car.ipk_setting.drive_info_reset")) return false;
        return !(normalized.endsWith("_action") || normalized.endsWith("_command") || normalized.endsWith("_request") ||
            normalized.endsWith("_oneshot") || normalized.contains(".request_"));
    }
    public synchronized void start() {
        if (closed || started) return;
        started = true;
        connect();
    }
    /** Prompt an immediate retry after a Shizuku lifecycle or permission change. */
    public synchronized void reconnect() {
        if (!started || closed || connection != null) return;
        cancelRetry();
        connect();
    }
    private void connect() {
        final long current = ++generation;
        report(Status.CONNECTING);
        if (closed || current != generation) return;
        try {
            ReceiptTracker receipts = new ReceiptTracker();
            Connection opened = transport.open(new Events() {
                public void onValue(String key, String value) { onValueReceived(key, value).run(); }
                public Runnable onValueReceived(String key, String value) {
                    if (!keySet.contains(key)) return () -> {};
                    long revision = receipts.record(key, value);
                    return () -> accept(current, receipts, key, value, revision);
                }
                public void onDeath() { died(current); }
            });
            if (opened == null) throw new Failure(Status.SERVICE_UNAVAILABLE);
            if (closed || current != generation) { opened.close(); return; }
            connection = opened;
            opened.subscribe(keys);
            if (!active(current)) return;
            report(Status.CONNECTED);
            if (!active(current)) return;
            for (String key : keys) {
                if (!active(current)) return;
                long before = receipts.revision(key);
                String value;
                try { value = opened.read(key); }
                catch (Failure transportFailure) { throw transportFailure; }
                catch (Exception propertyFailure) { continue; }
                if (!active(current)) return;
                if (validValue(value) && receipts.snapshot(key, before)) emit(key, value);
            }
        } catch (Exception ex) {
            if (closed || current != generation) return;
            disconnect();
            report(ex instanceof Failure ? ((Failure) ex).status : Status.ERROR);
            scheduleRetry();
        }
    }
    private synchronized void accept(long current, ReceiptTracker receipts, String key, String value, long revision) {
        if (!active(current) || !keySet.contains(key)) return;
        if (VIN_KEY.equals(key)) {
            // A newer Binder thread can post first. Drain earlier VIN receipts before it,
            // even if their own runnables have not yet reached the worker queue.
            for (Receipt receipt : receipts.takeVinThrough(revision)) {
                if (!active(current)) return;
                deliver(receipts, key, receipt.value, receipt.revision);
            }
        } else deliver(receipts, key, value, revision);
    }
    private void deliver(ReceiptTracker receipts, String key, String value, long revision) {
        // Malformed, nonempty VINs are still forwarded for the observer to invalidate,
        // but can be superseded by a later invalid receipt just like any other value.
        if (!receipts.deliver(key, revision, validValue(value))) return;
        if (validValue(value)) emit(key, value);
        else try { observer.onInvalidValue(key); } catch (RuntimeException ignored) { }
    }
    private static boolean validValue(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 16384;
    }
    private static boolean validReceipt(String key, String value) {
        return validValue(value) && (!VIN_KEY.equals(key) || VehicleTopicIdentity.INSTANCE.isValidVin(value));
    }
    private void emit(String key, String value) {
        if (!validValue(value)) return;
        try { observer.onValue(key, value); } catch (RuntimeException ignored) { }
    }
    private static final class Receipt {
        final long revision;
        final String value;
        Receipt(long revision, String value) { this.revision = revision; this.value = value; }
    }
    /** Independent lock: Binder threads must record receipt while the worker is in read(). */
    private static final class ReceiptTracker {
        private final Map<String, Long> revisions = new HashMap<>();
        private final Map<String, Long> delivered = new HashMap<>();
        private final Map<String, Long> invalidRevisions = new HashMap<>();
        private final Map<String, Long> invalidDelivered = new HashMap<>();
        private final NavigableMap<Long, Receipt> pendingVin = new TreeMap<>();
        synchronized long record(String key, String value) {
            long next = revisions.getOrDefault(key, 0L) + 1;
            revisions.put(key, next);
            if (!validReceipt(key, value)) invalidRevisions.put(key, next);
            if (VIN_KEY.equals(key)) pendingVin.put(next, new Receipt(next, value));
            return next;
        }
        synchronized List<Receipt> takeVinThrough(long revision) {
            NavigableMap<Long, Receipt> ready = pendingVin.headMap(revision, true);
            List<Receipt> result = new ArrayList<>(ready.values());
            ready.clear();
            return result;
        }
        synchronized long revision(String key) { return revisions.getOrDefault(key, 0L); }
        synchronized boolean snapshot(String key, long before) {
            if (revision(key) != before || before > delivered.getOrDefault(key, 0L)) return false;
            delivered.put(key, before);
            return true;
        }
        synchronized boolean deliver(String key, long revision, boolean valid) {
            if (valid) {
                if (revision < invalidRevisions.getOrDefault(key, 0L) || revision <= delivered.getOrDefault(key, 0L)) return false;
            } else {
                if (revision <= invalidDelivered.getOrDefault(key, 0L)) return false;
                invalidDelivered.put(key, revision);
            }
            delivered.put(key, Math.max(revision, delivered.getOrDefault(key, 0L)));
            return true;
        }
    }
    private synchronized void died(long current) {
        if (!active(current)) return;
        disconnect();
        report(Status.SERVICE_UNAVAILABLE);
        scheduleRetry();
    }
    private boolean active(long current) { return !closed && connection != null && current == generation; }
    private void disconnect() {
        ++generation;
        Connection old = connection;
        connection = null;
        if (old != null) try { old.close(); } catch (RuntimeException ignored) { }
    }
    private void scheduleRetry() {
        if (closed || retry != null) return;
        retry = scheduler.schedule(RETRY_MS, () -> { synchronized (VehicleSource.this) {
            retry = null;
            if (!closed && connection == null) connect();
        }});
    }
    private void cancelRetry() { if (retry != null) { retry.cancel(); retry = null; } }
    private void report(Status status) { try { observer.onStatus(status); } catch (RuntimeException ignored) { } }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cancelRetry();
        disconnect();
        report(Status.CLOSED);
    }
}
