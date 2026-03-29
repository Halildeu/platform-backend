package com.example.commonauth.scope;

/**
 * ThreadLocal holder for {@link ScopeContext}.
 * Uses InheritableThreadLocal so @Async and child threads
 * inherit the parent's scope context.
 *
 * Cleared automatically by {@link ScopeContextFilter} after each request.
 */
public final class ScopeContextHolder {

    private static final InheritableThreadLocal<ScopeContext> HOLDER = new InheritableThreadLocal<>();

    private ScopeContextHolder() {
    }

    public static ScopeContext get() {
        return HOLDER.get();
    }

    public static void set(ScopeContext context) {
        if (context == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(context);
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
