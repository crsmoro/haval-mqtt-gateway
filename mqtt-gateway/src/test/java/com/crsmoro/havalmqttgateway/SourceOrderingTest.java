package com.crsmoro.havalmqttgateway;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SourceOrderingTest {
    private static final String VIN = "car.basic.vin_code";
    private static final String VALID = "TESTAAA123456789A";

    @Test public void invertedNullAndValidDeliveriesInvalidateBeforeRestoration() {
        assertInvertedInvalidation(null);
    }

    @Test public void invertedMalformedAndValidDeliveriesInvalidateBeforeRestoration() {
        assertInvertedInvalidation("bad");
    }

    private void assertInvertedInvalidation(String invalid) {
        VehicleSourceTest.FakeTransport transport = new VehicleSourceTest.FakeTransport();
        List<String> events = new ArrayList<>();
        VehicleSource source = new VehicleSource(Collections.singletonList(VIN), transport,
            new VehicleSourceTest.ManualScheduler(), new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { events.add(value); }
                public void onInvalidValue(String key) { events.add("<invalid>"); }
            });
        source.start();
        events.clear();
        Runnable invalidDelivery = transport.listener.onValueReceived(VIN, invalid);
        Runnable validDelivery = transport.listener.onValueReceived(VIN, VALID);
        validDelivery.run();
        invalidDelivery.run();
        assertEquals(Arrays.asList(invalid == null ? "<invalid>" : invalid, VALID), events);
        source.close();
    }

    @Test public void malformedVinSuppressesOlderQueuedValidButStillInvalidates() {
        VehicleSourceTest.FakeTransport transport = new VehicleSourceTest.FakeTransport();
        List<String> events = new ArrayList<>();
        VehicleSource source = new VehicleSource(Collections.singletonList(VIN), transport,
            new VehicleSourceTest.ManualScheduler(), new VehicleSource.Observer() {
                public void onStatus(VehicleSource.Status status) {}
                public void onValue(String key, String value) { events.add(value); }
                public void onInvalidValue(String key) { events.add("<invalid>"); }
            });
        source.start();
        events.clear();
        Runnable old = transport.listener.onValueReceived(VIN, VALID);
        Runnable malformed = transport.listener.onValueReceived(VIN, "bad");
        malformed.run();
        old.run();
        assertEquals(Collections.singletonList("bad"), events);
        source.close();
    }

    @Test public void invalidVinDuringReadPreventsStaleSnapshotEvenWhenDeliveriesInvert() {
        for (String invalid : Arrays.asList(null, "bad")) {
            List<Runnable> queue = new ArrayList<>();
            List<String> events = new ArrayList<>();
            VehicleSource source = new VehicleSource(Collections.singletonList(VIN), listener ->
                new VehicleSource.Connection() {
                    public void subscribe(List<String> keys) {}
                    public String read(String key) {
                        queue.add(listener.onValueReceived(key, invalid));
                        queue.add(listener.onValueReceived(key, VALID));
                        return "snapshot";
                    }
                    public void close() {}
                }, new VehicleSourceTest.ManualScheduler(), new VehicleSource.Observer() {
                    public void onStatus(VehicleSource.Status status) {}
                    public void onValue(String key, String value) { events.add(value); }
                    public void onInvalidValue(String key) { events.add("<invalid>"); }
                });
            source.start();
            queue.get(1).run();
            queue.get(0).run();
            assertEquals(Arrays.asList(invalid == null ? "<invalid>" : invalid, VALID), events);
            source.close();
        }
    }
}
