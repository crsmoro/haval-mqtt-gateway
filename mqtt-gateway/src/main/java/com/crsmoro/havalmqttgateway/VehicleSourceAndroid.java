package com.crsmoro.havalmqttgateway;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/** Android transport for VehicleSource. No Binder transaction that executes vehicle commands is exposed. */
public final class VehicleSourceAndroid implements AutoCloseable {
    private static final String SERVICE = "com.beantechs.intelligentvehiclecontrol";
    private static final String CONTROL = "com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService";
    private static final String LISTENER = "com.beantechs.intelligentvehiclecontrol.sdk.IListener";
    // OEM wire layout: slot 0 is a command and is deliberately never invoked.
    private static final int FETCH = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int ADD_KEYS = IBinder.FIRST_CALL_TRANSACTION + 3;
    private static final int REGISTER = IBinder.FIRST_CALL_TRANSACTION + 4;
    private static final int UNREGISTER = IBinder.FIRST_CALL_TRANSACTION + 5;
    private final HandlerThread worker = new HandlerThread("vehicle-source");
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final VehicleSource source;
    private final OemTransport transport;
    private final Shizuku.OnBinderReceivedListener received;
    private final Shizuku.OnBinderDeadListener dead;
    private final Shizuku.OnRequestPermissionResultListener permission;

    /** All observer calls are made on an internal worker thread. Call close when no longer needed. */
    public VehicleSourceAndroid(Context context, List<String> readOnlyKeys, VehicleSource.Observer observer) {
        if (context == null) throw new IllegalArgumentException("context required");
        worker.start();
        handler = new Handler(worker.getLooper());
        transport = new OemTransport(context.getApplicationContext().getPackageName());
        try {
            source = new VehicleSource(readOnlyKeys, transport, (delay, task) -> {
                handler.postDelayed(task, delay);
                return () -> handler.removeCallbacks(task);
            }, observer);
        } catch (RuntimeException e) {
            worker.quitSafely();
            throw e;
        }
        received = () -> source.reconnect();
        dead = () -> { if (transport.events != null) transport.events.onDeath(); };
        permission = (requestCode, grantResult) -> source.reconnect();
    }
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        handler.post(() -> {
            if (closed.get()) return;
            Shizuku.addBinderReceivedListenerSticky(received, handler);
            Shizuku.addBinderDeadListener(dead, handler);
            Shizuku.addRequestPermissionResultListener(permission, handler);
            source.start();
        });
    }
    /** Invoke after an explicit user action if permission has not yet been granted. */
    public void requestPermission(int requestCode) {
        if (closed.get()) return;
        handler.post(() -> {
            if (!closed.get() && Shizuku.pingBinder()) {
                try { Shizuku.requestPermission(requestCode); } catch (RuntimeException ignored) { source.reconnect(); }
            }
        });
    }
    public void reconnect() { if (!closed.get()) handler.post(source::reconnect); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> {
            Shizuku.removeBinderReceivedListener(received);
            Shizuku.removeBinderDeadListener(dead);
            Shizuku.removeRequestPermissionResultListener(permission);
            source.close();
            worker.quitSafely();
        };
        if (Looper.myLooper() == worker.getLooper()) cleanup.run(); else handler.post(cleanup);
    }

    private final class OemTransport implements VehicleSource.Transport {
        private final String packageName;
        private VehicleSource.Events events;
        OemTransport(String packageName) { this.packageName = packageName; }
        @Override public VehicleSource.Connection open(VehicleSource.Events events) throws Exception {
            if (!Shizuku.pingBinder()) throw new VehicleSource.Failure(VehicleSource.Status.SHIZUKU_UNAVAILABLE);
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                throw new VehicleSource.Failure(VehicleSource.Status.PERMISSION_DENIED);
            // Do not use SystemServiceHelper's static cache: a dead OEM binder must be reacquired.
            IBinder original;
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                original = (IBinder) sm.getMethod("getService", String.class).invoke(null, SERVICE);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                throw new VehicleSource.Failure(VehicleSource.Status.SERVICE_UNAVAILABLE);
            }
            if (original == null || !original.isBinderAlive())
                throw new VehicleSource.Failure(VehicleSource.Status.SERVICE_UNAVAILABLE);
            OemConnection result = new OemConnection(original, events, packageName);
            this.events = events;
            return result;
        }
    }
    private final class OemConnection implements VehicleSource.Connection {
        private final IBinder original;
        private final IBinder remote;
        private final String packageName;
        private final VehicleSource.Events events;
        private boolean closedConnection;
        private boolean registrationAttempted;
        private final IBinder.DeathRecipient death;
        private final Binder listener;
        OemConnection(IBinder original, VehicleSource.Events events, String packageName) throws RemoteException {
            this.original = original;
            this.remote = new ShizukuBinderWrapper(original);
            this.events = events;
            this.packageName = packageName;
            death = () -> handler.post(events::onDeath);
            listener = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == IBinder.INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(LISTENER); return true; }
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                    data.enforceInterface(LISTENER);
                    String key = data.readString();
                    String value = data.readString();
                    handler.post(events.onValueReceived(key, value));
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            };
            original.linkToDeath(death, 0);
        }
        @Override public void subscribe(List<String> keys) throws Exception {
            registrationAttempted = true;
            transact(REGISTER, p -> { p.writeString(packageName); p.writeStrongBinder(listener); }, null);
            transact(ADD_KEYS, p -> { p.writeString(packageName); p.writeStringArray(keys.toArray(new String[0])); }, null);
        }
        @Override public String read(String key) throws Exception {
            String[] result = new String[1];
            try {
                transact(FETCH, p -> p.writeString(key), p -> result[0] = p.readString());
            } catch (RemoteException ex) {
                throw new VehicleSource.Failure(VehicleSource.Status.SERVICE_UNAVAILABLE);
            } catch (RuntimeException ex) {
                if (!original.isBinderAlive() || !Shizuku.pingBinder())
                    throw new VehicleSource.Failure(VehicleSource.Status.SERVICE_UNAVAILABLE);
                throw ex;
            }
            return result[0];
        }
        private void transact(int code, ParcelWriter writer, ParcelReader reader) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL);
                writer.write(data);
                if (!remote.transact(code, data, reply, 0)) throw new RemoteException("OEM transaction failed");
                reply.readException();
                if (reader != null) reader.read(reply);
            } finally { reply.recycle(); data.recycle(); }
        }
        @Override public void close() {
            if (closedConnection) return;
            closedConnection = true;
            if (transport.events == events) transport.events = null;
            if (registrationAttempted) {
                try { transact(UNREGISTER, p -> { p.writeString(packageName); p.writeStrongBinder(listener); }, null); }
                catch (Exception ignored) { }
            }
            original.unlinkToDeath(death, 0);
        }
    }
    private interface ParcelWriter { void write(Parcel parcel); }
    private interface ParcelReader { void read(Parcel parcel); }
}
