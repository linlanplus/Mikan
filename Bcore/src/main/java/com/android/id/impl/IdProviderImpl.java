package com.android.id.impl;

import android.content.Context;

import top.niunaijun.blackbox.fake.device.VendorIdProvider;

/**
 * A clone's own copy of the MIUI identifier provider.
 *
 * <p>The class name has to match the one an OAID SDK looks up: the runtime refuses a
 * ClassLoader.loadClass that returns a class of another name, so a differently named替身 cannot be
 * handed back. It lives in this package only inside the host dex, and reaches the guest through
 * {@link top.niunaijun.blackbox.fake.device.VendorClassShim}.
 *
 * <p>Signatures follow the MIUI original: an OAID SDK instantiates it and calls one getter with a
 * Context it does not actually need.
 */
public final class IdProviderImpl {

    public IdProviderImpl() {
    }

    public IdProviderImpl(Context context) {
    }

    public String getOAID(Context context) {
        return VendorIdProvider.getOAID();
    }

    /** Some MIUI builds expose the argument-less form. */
    public String getOAID() {
        return VendorIdProvider.getOAID();
    }

    public String getVAID(Context context) {
        return VendorIdProvider.getVAID();
    }

    public String getAAID(Context context) {
        return VendorIdProvider.getAAID();
    }
}
