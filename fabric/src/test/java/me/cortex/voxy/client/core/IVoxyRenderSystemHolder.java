package me.cortex.voxy.client.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test stub of the 0.2.18-beta renderer-holder mixin interface (the reset resolver's
 * primary rung). The real interface carries the static {@code getNullableHolder()}
 * the 26.2 Voxy build's own reload uses (bytecode-verified) — set {@link #HOLDER}
 * to control what it returns.
 */
public interface IVoxyRenderSystemHolder {

    AtomicReference<IVoxyRenderSystemHolder> HOLDER = new AtomicReference<>();

    static IVoxyRenderSystemHolder getNullableHolder() {
        return HOLDER.get();
    }

    void voxy$shutdownRenderer();
}
