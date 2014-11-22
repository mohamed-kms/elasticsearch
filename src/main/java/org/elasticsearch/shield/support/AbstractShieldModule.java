/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.support;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.SpawnModules;
import org.elasticsearch.common.settings.Settings;

/**
 *
 */
public abstract class AbstractShieldModule extends AbstractModule {

    protected final Settings settings;
    protected final boolean clientMode;
    protected final boolean shieldEnabled;

    public AbstractShieldModule(Settings settings) {
        this.settings = settings;
        this.clientMode = !"node".equals(settings.get(Client.CLIENT_TYPE_SETTING));
        this.shieldEnabled = settings.getAsBoolean("shield.enabled", true);
    }

    @Override
    protected final void configure() {
        configure(clientMode);
    }

    protected abstract void configure(boolean clientMode);

    public static abstract class Spawn extends AbstractShieldModule implements SpawnModules {

        protected Spawn(Settings settings) {
            super(settings);
        }

        @Override
        public final Iterable<? extends Module> spawnModules() {
            return spawnModules(clientMode);
        }

        public abstract Iterable<? extends Module> spawnModules(boolean clientMode);
    }

    public static abstract class Node extends AbstractShieldModule {

        protected Node(Settings settings) {
            super(settings);
        }

        @Override
        protected final void configure(boolean clientMode) {
            assert !clientMode : "[" + getClass().getSimpleName() + "] is a node only module";
            configureNode();
        }

        protected abstract void configureNode();

        public static abstract class Spawn extends Node implements SpawnModules {

            protected Spawn(Settings settings) {
                super(settings);
            }

            public abstract Iterable<? extends AbstractShieldModule.Node> spawnModules();
        }

    }
}
