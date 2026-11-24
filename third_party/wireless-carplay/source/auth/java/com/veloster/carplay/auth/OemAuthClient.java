package com.veloster.carplay.auth;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** API17 Binder adapter for the observed OEM iAP2 service; no direct CP access.
 * Obtain the binder through Context.bindService(serviceIntent(), connection, BIND_AUTO_CREATE).
 * Call from a worker thread: synchronous Binder has no cancellable per-call timeout.
 * The caller owns binding/unbinding and validates the installed OEM artifact identities.
 */
public final class OemAuthClient {
    public static final String DESCRIPTOR = "com.tcc.iap2.IIap2Service";
    public static final String SERVICE_ACTION = "com.remote.service.IAP2SERVICE";
    public static final String SERVICE_PACKAGE = "com.tcc.iap2server";
    public static final String SERVICE_CLASS = "com.tcc.iap2server.Iap2Service";
    public static final int MAX_RESULT_BYTES = 65535;
    private static final int COPY_CERTIFICATE = 10;
    private static final int CREATE_SIGNATURE = 11;
    private final IBinder remote;

    public static final class AuthException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String reason;
        private AuthException(String reason) { super(reason); this.reason = reason; }
    }

    public OemAuthClient(IBinder binder) throws AuthException {
        if (binder == null) throw failure("service_unavailable");
        try {
            if (!binder.isBinderAlive()) throw failure("service_disconnected");
            if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) throw failure("wrong_interface");
        } catch (RemoteException error) {
            throw failure("service_disconnected");
        } catch (RuntimeException error) {
            throw failure("service_rejected");
        }
        remote = binder;
    }

    public static Intent serviceIntent() {
        return new Intent(SERVICE_ACTION).setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
    }

    public synchronized byte[] copyCertificate() throws AuthException {
        return call(COPY_CERTIFICATE, null);
    }

    public synchronized byte[] createSignature(byte[] challenge) throws AuthException {
        if (challenge == null || challenge.length != 20) throw failure("invalid_challenge_length");
        byte[] copy = challenge.clone();
        try {
            return call(CREATE_SIGNATURE, copy);
        } finally {
            java.util.Arrays.fill(copy, (byte) 0);
        }
    }

    private byte[] call(int transaction, byte[] challenge) throws AuthException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!remote.isBinderAlive()) throw failure("service_disconnected");
            request.writeInterfaceToken(DESCRIPTOR);
            if (challenge != null) {
                request.writeInt(1); // Parcelable present.
                request.writeInt(challenge.length); // OEM byteLen field.
                request.writeByteArray(challenge); // Encodes its own length.
            }
            if (!remote.transact(transaction, request, reply, 0)) throw failure("unsupported_transaction");
            if (reply.dataSize() < 8 || reply.dataSize() > 16 + aligned(MAX_RESULT_BYTES)) {
                throw failure("invalid_reply_size");
            }
            reply.setDataPosition(0);
            reply.readException();
            if (reply.dataAvail() < 4) throw failure("truncated_reply");
            if (reply.readInt() != 1) throw failure("authentication_unavailable");
            if (reply.dataAvail() < 8) throw failure("truncated_reply");
            int declared = reply.readInt();
            int arrayPosition = reply.dataPosition();
            int encoded = reply.readInt();
            if (declared <= 0 || declared > MAX_RESULT_BYTES || encoded != declared) {
                throw failure("invalid_result_length");
            }
            if (reply.dataAvail() != aligned(declared)) throw failure("invalid_result_boundary");
            // Allocate only after both lengths and the complete payload have been bounded.
            byte[] value = new byte[declared];
            reply.setDataPosition(arrayPosition);
            reply.readByteArray(value);
            if (reply.dataAvail() != 0) throw failure("trailing_reply_data");
            return value;
        } catch (RemoteException error) {
            throw failure("service_disconnected");
        } catch (RuntimeException error) {
            // Never include remote messages, request data or response contents in errors.
            throw failure("service_rejected");
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static int aligned(int size) { return (size + 3) & ~3; }
    private static AuthException failure(String reason) { return new AuthException(reason); }
}
