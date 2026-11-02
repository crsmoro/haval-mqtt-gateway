package com.crsmoro.havalmqttgateway;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class VehicleSourceTest {
    @Test public void subscribesBeforeSnapshotAndForwardsOnlyRequestedKeys() {
        FakeTransport t = new FakeTransport();
        List<String> events = new ArrayList<>();
        VehicleSource s = new VehicleSource(Arrays.asList("car.basic.vehicle_speed"), t,
            (delay, task) -> () -> {}, new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { events.add(key + "=" + value); }
            });
        s.start();
        assertEquals(Arrays.asList("open", "subscribe", "read"), t.calls);
        assertEquals(Collections.singletonList("car.basic.vehicle_speed=42"), events);
        t.listener.onValue("car.basic.vehicle_speed", "43");
        t.listener.onValue("car.basic.vin_code", "secret");
        assertEquals(Arrays.asList("car.basic.vehicle_speed=42", "car.basic.vehicle_speed=43"), events);
        s.close();
        assertEquals("close", t.calls.get(t.calls.size() - 1));
    }
    @Test public void reconnectsAfterDeathAndIgnoresOldCallbacks() {
        FakeTransport t = new FakeTransport();
        ManualScheduler timer = new ManualScheduler();
        List<String> values = new ArrayList<>();
        List<VehicleSource.Status> statuses = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), t, timer,
            observer(values, statuses));
        s.start();
        VehicleSource.Events old = t.listener;
        old.onDeath();
        assertEquals(VehicleSource.Status.SERVICE_UNAVAILABLE, statuses.get(statuses.size() - 1));
        assertEquals(1, timer.pending.size());
        timer.run();
        assertEquals(2, Collections.frequency(t.calls, "open"));
        old.onValue("car.basic.vehicle_speed", "stale");
        assertEquals(Arrays.asList("42", "42"), values);
        s.close();
        t.listener.onValue("car.basic.vehicle_speed", "late");
        assertEquals(2, values.size());
        assertEquals(VehicleSource.Status.CLOSED, statuses.get(statuses.size() - 1));
    }
    @Test public void subscriptionFailureClosesPartialConnectionBeforeRetry() {
        ManualScheduler timer = new ManualScheduler();
        List<String> calls = new ArrayList<>();
        List<VehicleSource.Status> statuses = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), events ->
            new VehicleSource.Connection() {
                public void subscribe(List<String> keys) throws Exception { calls.add("subscribe"); throw new Exception(); }
                public String read(String key) { calls.add("read"); return "42"; }
                public void close() { calls.add("close"); }
            }, timer, observer(new ArrayList<>(), statuses));
        s.start();
        assertEquals(Arrays.asList("subscribe", "close"), calls);
        assertEquals(VehicleSource.Status.ERROR, statuses.get(statuses.size()-1));
        assertEquals(1, timer.pending.size());
        s.close();
    }
    @Test public void shizukuUnavailableIsReported() {
        List<VehicleSource.Status> statuses = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), events -> {
            throw new VehicleSource.Failure(VehicleSource.Status.SHIZUKU_UNAVAILABLE);
        }, new ManualScheduler(), observer(new ArrayList<>(), statuses));
        s.start();
        assertEquals(VehicleSource.Status.SHIZUKU_UNAVAILABLE, statuses.get(statuses.size()-1));
        s.close();
    }
    @Test public void permissionFailureRetriesAndCloseCancels() {
        ManualScheduler timer = new ManualScheduler();
        List<VehicleSource.Status> statuses = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), events -> {
            throw new VehicleSource.Failure(VehicleSource.Status.PERMISSION_DENIED);
        }, timer, observer(new ArrayList<>(), statuses));
        s.start();
        assertEquals(VehicleSource.Status.PERMISSION_DENIED, statuses.get(statuses.size()-1));
        assertEquals(1, timer.pending.size());
        s.close();
        assertTrue(timer.pending.isEmpty());
    }
    @Test public void allowsRequestedVehicleIdentifiersAndSensors() {
        FakeTransport t = new FakeTransport();
        List<String> values = new ArrayList<>();
        VehicleSource s = new VehicleSource(Arrays.asList("car.basic.vin_code", "car.dms.face"), t,
            new ManualScheduler(), observer(values, new ArrayList<>()));
        s.start();
        assertEquals(Arrays.asList("42", "42"), values);
    }
    @Test public void preservesMixedCaseOemVehicleIdentifiers() {
        assertTrue(VehicleSource.permitsKey("car.basic.left_turn_Indicator_light_status"));
        assertTrue(VehicleSource.permitsKey("car.oms.frs.passengerL_row1_expression"));
        assertFalse(VehicleSource.permitsKey("car.basic.door_CONTROL_ACTION"));
    }
    @Test public void selectionCanBeCheckedBeforeConstruction() {
        assertTrue(VehicleSource.permitsKey("car.basic.vin_code"));
        assertFalse(VehicleSource.permitsKey("car.map.tsr.nav_speed_limit"));
    }
    @Test public void rejectsNonVehicleAndCommandKeys() {
        for (String key : Arrays.asList("car.window_action", "app.example", "sys.navigation", "android.auto", "car.map.tsr.nav_speed_limit", "car.configure.navigation", "car.basic.gps_latitude", "car.android_auto.status", "haval.power.flow")) {
            try {
                new VehicleSource(Collections.singletonList(key), new FakeTransport(), new ManualScheduler(), observer(new ArrayList<>(), new ArrayList<>()));
                fail("accepted " + key);
            } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void closeFromConnectingObserverDoesNotOpenTransport() {
        FakeTransport t = new FakeTransport();
        VehicleSource[] holder = new VehicleSource[1];
        holder[0] = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), t, new ManualScheduler(),
            new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) { if (status == VehicleSource.Status.CONNECTING) holder[0].close(); }
                public void onValue(String key, String value) { fail("unexpected value"); }
            });
        holder[0].start();
        assertTrue(t.calls.isEmpty());
    }
    @Test public void closeFromConnectedObserverDoesNotRead() {
        FakeTransport t = new FakeTransport();
        VehicleSource[] holder = new VehicleSource[1];
        holder[0] = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), t, new ManualScheduler(),
            new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) { if (status == VehicleSource.Status.CONNECTED) holder[0].close(); }
                public void onValue(String key, String value) { fail("unexpected value"); }
            });
        holder[0].start();
        assertEquals(Arrays.asList("open", "subscribe", "close"), t.calls);
    }
    @Test public void closeFromSnapshotObserverStopsFurtherReads() {
        FakeTransport t = new FakeTransport();
        VehicleSource[] holder = new VehicleSource[1];
        holder[0] = new VehicleSource(Arrays.asList("car.basic.vehicle_speed", "car.basic.rpm"), t, new ManualScheduler(),
            new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { holder[0].close(); }
            });
        holder[0].start();
        assertEquals(Arrays.asList("open", "subscribe", "read", "close"), t.calls);
    }
    @Test public void synchronousUpdateDuringReadDoesNotGetOverwrittenBySnapshot() {
        FakeTransport t = new FakeTransport() {
            public VehicleSource.Connection open(VehicleSource.Events e) {
                listener = e;
                return new VehicleSource.Connection() {
                    public void subscribe(List<String> keys) {}
                    public String read(String key) { listener.onValue(key, "new"); return "old"; }
                    public void close() {}
                };
            }
        };
        List<String> values = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), t, new ManualScheduler(), observer(values, new ArrayList<>()));
        s.start();
        assertEquals(Collections.singletonList("new"), values);
    }
    @Test public void queuedSubscriptionCallbackIsNotDiscardedBySnapshot() {
        List<Runnable> queue = new ArrayList<>();
        List<String> values = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), events ->
            new VehicleSource.Connection() {
                public void subscribe(List<String> keys) { queue.add(receive(events, keys.get(0), "callback")); }
                public String read(String key) { return "snapshot"; }
                public void close() {}
            }, new ManualScheduler(), observer(values, new ArrayList<>()));
        s.start();
        queue.forEach(Runnable::run);
        assertEquals(Collections.singletonList("callback"), values);
        s.close();
    }
    @Test public void invalidCallbackDuringReadSuppressesSnapshotAndSignalsInvalidation() {
        for (String invalid : Arrays.asList(null, " ", "x".repeat(16385))) {
            List<Runnable> queue = new ArrayList<>();
            List<String> values = new ArrayList<>();
            List<String> invalidated = new ArrayList<>();
            VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.engine_speed"), events ->
                new VehicleSource.Connection() {
                    public void subscribe(List<String> keys) {}
                    public String read(String key) { queue.add(receive(events, key, invalid)); return "123"; }
                    public void close() {}
                }, new ManualScheduler(), new VehicleSource.Observer() {
                    public void onStatus(VehicleSource.Status status) {}
                    public void onValue(String key, String value) { values.add(value); }
                    public void onInvalidValue(String key) { invalidated.add(key); }
                });
            s.start();
            queue.forEach(Runnable::run);
            assertTrue(values.isEmpty());
            assertEquals(Collections.singletonList("car.basic.engine_speed"), invalidated);
            s.close();
        }
    }
    @Test public void newerInvalidReceiptSuppressesOlderQueuedValue() {
        FakeTransport t = new FakeTransport();
        List<String> values = new ArrayList<>();
        List<String> invalidated = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vin_code"), t, new ManualScheduler(),
            new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { values.add(value); }
                public void onInvalidValue(String key) { invalidated.add(key); }
            });
        s.start();
        values.clear();
        Runnable old = receive(t.listener, "car.basic.vin_code", "old");
        Runnable invalid = receive(t.listener, "car.basic.vin_code", null);
        old.run();
        invalid.run();
        assertTrue(values.isEmpty());
        assertEquals(Collections.singletonList("car.basic.vin_code"), invalidated);
        s.close();
    }
    @Test public void queuedValidValuesPreserveIntermediateIdentityChanges() {
        FakeTransport t = new FakeTransport();
        List<String> values = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vin_code"), t,
            new ManualScheduler(), observer(values, new ArrayList<>()));
        s.start();
        values.clear();
        Runnable invalidVin = receive(t.listener, "car.basic.vin_code", "invalid");
        Runnable restoredVin = receive(t.listener, "car.basic.vin_code", "TESTAAA123456789A");
        invalidVin.run();
        restoredVin.run();
        assertEquals(Arrays.asList("invalid", "TESTAAA123456789A"), values);
        s.close();
    }
    @Test public void lateInvalidationStillClearsStateAfterNewerValueDelivery() {
        FakeTransport t = new FakeTransport();
        List<String> events = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.engine_speed"), t,
            new ManualScheduler(), new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { events.add(value); }
                public void onInvalidValue(String key) { events.add("invalid"); }
            });
        s.start();
        events.clear();
        Runnable invalid = receive(t.listener, "car.basic.engine_speed", null);
        Runnable valid = receive(t.listener, "car.basic.engine_speed", "300");
        valid.run();
        invalid.run();
        assertEquals(Arrays.asList("300", "invalid"), events);
        s.close();
    }
    @Test public void queuedInvalidationIsDeliveredBeforeNewerValidValue() {
        FakeTransport t = new FakeTransport();
        List<String> events = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.engine_speed"), t, new ManualScheduler(),
            new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { events.add(value); }
                public void onInvalidValue(String key) { events.add("invalid"); }
            });
        s.start();
        events.clear();
        Runnable invalid = receive(t.listener, "car.basic.engine_speed", null);
        Runnable valid = receive(t.listener, "car.basic.engine_speed", "300");
        invalid.run();
        valid.run();
        assertEquals(Arrays.asList("invalid", "300"), events);
        s.close();
    }
    @Test public void callbackReceivedDuringReadWinsOverSnapshotEvenWhenDeliveryIsQueued() throws Exception {
        List<Runnable> queue = new ArrayList<>();
        List<String> values = new ArrayList<>();
        VehicleSource s = new VehicleSource(Collections.singletonList("car.basic.vehicle_speed"), events ->
            new VehicleSource.Connection() {
                public void subscribe(List<String> keys) {}
                public String read(String key) throws Exception {
                    Thread binderThread = new Thread(() -> queue.add(receive(events, key, "callback")));
                    binderThread.start();
                    binderThread.join(2000);
                    assertFalse("callback receipt blocked on snapshot", binderThread.isAlive());
                    return "snapshot";
                }
                public void close() {}
            }, new ManualScheduler(), observer(values, new ArrayList<>()));
        s.start();
        queue.forEach(Runnable::run);
        assertEquals(Collections.singletonList("callback"), values);
        s.close();
    }
    private static Runnable receive(VehicleSource.Events events, String key, String value) {
        try {
            return (Runnable) VehicleSource.Events.class.getMethod("onValueReceived", String.class, String.class)
                .invoke(events, key, value);
        } catch (NoSuchMethodException beforeFix) {
            return () -> events.onValue(key, value);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    @Test public void unsupportedSnapshotPropertyDoesNotDisconnectOtherProperties() {
        ManualScheduler timer = new ManualScheduler();
        List<String> values = new ArrayList<>();
        List<VehicleSource.Status> statuses = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        VehicleSource.Events[] listener = new VehicleSource.Events[1];
        VehicleSource s = new VehicleSource(Arrays.asList("car.basic.unsupported", "car.basic.vehicle_speed"), events -> {
            listener[0] = events;
            return new VehicleSource.Connection() {
                public void subscribe(List<String> keys) { calls.add("subscribe"); }
                public String read(String key) {
                    calls.add(key);
                    if (key.endsWith("unsupported")) throw new IllegalArgumentException("private OEM details");
                    return "42";
                }
                public void close() { calls.add("close"); }
            };
        }, timer, observer(values, statuses));
        s.start();
        listener[0].onValue("car.basic.vehicle_speed", "43");
        assertEquals(Arrays.asList("42", "43"), values);
        assertEquals(Arrays.asList("subscribe", "car.basic.unsupported", "car.basic.vehicle_speed"), calls);
        assertEquals(VehicleSource.Status.CONNECTED, statuses.get(statuses.size() - 1));
        assertTrue(timer.pending.isEmpty());
        s.close();
    }
    static VehicleSource.Observer observer(List<String> values, List<VehicleSource.Status> statuses) {
        return new VehicleSource.Observer() {
            public void onStatus(VehicleSource.Status status) { statuses.add(status); }
            public void onValue(String key, String value) { values.add(value); }
        };
    }
    static class ManualScheduler implements VehicleSource.Scheduler {
        final List<Runnable> pending = new ArrayList<>();
        public VehicleSource.Cancel schedule(long delay, Runnable task) { pending.add(task); return () -> pending.remove(task); }
        void run() { pending.remove(0).run(); }
    }
    static class FakeTransport implements VehicleSource.Transport {
        final List<String> calls = new ArrayList<>();
        VehicleSource.Events listener;
        public VehicleSource.Connection open(VehicleSource.Events events) {
            calls.add("open"); listener = events;
            return new VehicleSource.Connection() {
                public void subscribe(List<String> keys) { calls.add("subscribe"); }
                public String read(String key) { calls.add("read"); return "42"; }
                public void close() { calls.add("close"); }
            };
        }
    }
}
