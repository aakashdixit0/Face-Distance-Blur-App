package com.facedistanceblur;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

public class FakeLifecycleOwner implements LifecycleOwner {
    private final LifecycleRegistry lifecycleRegistry;

    public FakeLifecycleOwner() {
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
}