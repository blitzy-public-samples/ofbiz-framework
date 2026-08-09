/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.service.jms;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.base.config.GenericConfigException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.config.ServiceConfigUtil;
import org.apache.ofbiz.service.config.model.JmsService;
import org.apache.ofbiz.service.config.model.ServiceEngine;
import org.apache.ofbiz.service.config.model.Server;

/**
 * JmsListenerFactory
 */
public class JmsListenerFactory implements Runnable {

    private static final String MODULE = JmsListenerFactory.class.getName();

    public static final String TOPIC_LISTENER_CLASS = "org.apache.ofbiz.service.jms.JmsTopicListener";
    public static final String QUEUE_LISTENER_CLASS = "org.apache.ofbiz.service.jms.JmsQueueListener";

    private static Map<String, GenericMessageListener> listeners = new ConcurrentHashMap<>();
    private static Map<String, Server> servers = new ConcurrentHashMap<>();

    private static final AtomicReference<JmsListenerFactory> JL_FACTORY_REF = new AtomicReference<>(null);

    /** Whether an unreadable JMS configuration has already been reported by {@link #readinessFailure}. */
    private static final AtomicBoolean CONFIG_FAULT_REPORTED = new AtomicBoolean();

    /** Guards creation of a listener for a server key, which is a subscription, against the shared map. */
    private static final Object LISTENER_CREATION_LOCK = new Object();

    private Delegator delegator;
    private boolean firstPass = true;
    private int loadable = 0;
    private int connected = 0;
    private Thread thread;


    /**
     * Returns the one listener factory of this JVM, creating and starting it on first use.
     *
     * <p><strong>Construction is serialised, and that is the point.</strong> The constructor starts a thread
     * which loads the listeners, and each loaded listener subscribes to its topic or queue - so an instance
     * that is built and then discarded does not stop being a broker consumer. Two callers that arrive here
     * together - and they do: every {@code ServiceDispatcher} created while the container starts calls this,
     * from whichever thread is loading its component - must therefore not each build one. Building outside a
     * lock and keeping only the winner of a compare-and-set, which is what this did, left the loser's thread
     * running with its subscription intact: one JVM then held two consumers on the cache-invalidation topic
     * and executed every invalidation twice, and a two-instance fleet showed three consumers where the
     * broker should have shown two. Duplicate invalidation is not merely wasted work - each one is a service
     * invocation carrying a transaction.
     *
     * <p>The lock is entered only while no instance exists yet, so the steady-state cost is the one volatile
     * read above it. The second read inside the lock is what makes the check-then-act safe.
     *
     * @param delegator the delegator the factory and its listeners run against
     * @return the singleton factory
     */
    public static JmsListenerFactory getInstance(Delegator delegator) {
        JmsListenerFactory instance = JL_FACTORY_REF.get();
        if (instance == null) {
            synchronized (JmsListenerFactory.class) {
                instance = JL_FACTORY_REF.get();
                if (instance == null) {
                    instance = new JmsListenerFactory(delegator);
                    JL_FACTORY_REF.set(instance);
                }
            }
        }
        return instance;
    }

    /**
     * Reports whether every JMS listener this service engine declares is connected, WITHOUT creating the
     * factory, a connection or a subscription.
     *
     * <p>Called from the readiness probe, reflectively-free because {@code framework/webapp} already depends
     * on {@code framework/service}. Three answers, in the same encoding the content store's readiness hook
     * uses:
     *
     * <ul>
     *   <li>{@code null} - <strong>not applicable.</strong> The service engine declares no listening JMS
     *       server, which is the shipped configuration: the {@code serviceMessenger} example in
     *       {@code serviceengine.xml} is commented out, and {@code docker/docker-entrypoint.sh} renders a
     *       real one only when a broker is configured. A deployment with no message bus reports nothing
     *       about one.</li>
     *   <li>the empty string - <strong>connected.</strong> Every declared listening server has a listener
     *       and every listener reports a live connection.</li>
     *   <li>anything else - <strong>not connected</strong>, and the string names what is missing.</li>
     * </ul>
     *
     * <p><strong>Why the listener answers for the broker.</strong> What readiness needs to know is whether
     * this instance can still take part in fleet-wide cache invalidation, and a listener's connection is the
     * observable proxy for that: a subscriber whose broker has gone is told through its
     * {@code ExceptionListener}, clears its connected flag and retries until the broker returns - so the flag
     * tracks broker reachability without this method opening a connection of its own, which is what keeps a
     * probe free. The sending half uses the same broker, so a listener that cannot reach it means an
     * invalidation cannot be published either.
     *
     * <p><strong>It never creates the factory.</strong> The listener map is static and is read directly. A
     * probe that called {@link #getInstance} would build the factory - and its subscription - in a JVM where
     * JMS had been left switched off, which is the opposite of observing.
     *
     * @return null when no listening JMS server is declared, the empty string when every declared listener is
     *     connected, otherwise the reason it is not
     */
    public static String readinessFailure() {
        int declared = 0;
        try {
            ServiceEngine engine = ServiceConfigUtil.getServiceEngine();
            for (JmsService service : engine.getJmsServices()) {
                for (Server server : service.getServers()) {
                    if (server.getListen()) {
                        declared++;
                    }
                }
            }
        } catch (GenericConfigException | RuntimeException unreadable) {
            // The same failure stops loadListeners() from loading anything at all - it reads exactly this
            // configuration - so there is no listener to report on and nothing about this instance's readiness
            // follows from it. Reported once and treated as "no JMS declared", deliberately: guessing the
            // other way would hold a whole fleet out of service over a question this method cannot answer.
            // RuntimeException as well as the declared one, because the accessor answers null for an engine
            // that is not configured and a readiness question must never propagate.
            if (CONFIG_FAULT_REPORTED.compareAndSet(false, true)) {
                Debug.logWarning(unreadable, "The JMS configuration could not be read, so readiness cannot report"
                        + " on message-bus connectivity. Reported once per instance.", MODULE);
            }
            return null;
        }
        if (declared == 0) {
            return null;
        }
        List<String> disconnected = new ArrayList<>();
        for (Map.Entry<String, GenericMessageListener> loaded : listeners.entrySet()) {
            if (!loaded.getValue().isConnected()) {
                disconnected.add(loaded.getKey());
            }
        }
        int connected = listeners.size() - disconnected.size();
        if (connected >= declared) {
            return "";
        }
        if (!disconnected.isEmpty()) {
            return "the JMS listener(s) " + disconnected + " are not connected to the message bus, so"
                    + " fleet-wide entity-cache invalidations can be neither sent nor received";
        }
        // Declared, not disconnected, and not present: the factory has not finished its first pass, or a
        // listener could not be constructed at all. Either way this instance is not yet carrying
        // invalidations, which is exactly what a probe should say during a rollout.
        return "only " + connected + " of " + declared + " declared JMS listener(s) have been loaded, so"
                + " fleet-wide entity-cache invalidations are not being carried yet";
    }

    /**
     * Builds a listener factory and starts the thread that loads and connects its listeners.
     *
     * <p>Constructing one SUBSCRIBES this JVM to every listening topic and queue the service engine declares,
     * so at most one may exist per JVM: use {@link #getInstance}, which is where that is enforced. An
     * instance built here and then discarded keeps its subscriptions and its thread, and a second consumer on
     * the entity-cache-invalidation topic makes this instance execute every invalidation twice.
     *
     * @param delegator the delegator the factory and its listeners run against
     */
    public JmsListenerFactory(Delegator delegator) {
        this.delegator = delegator;
        thread = new Thread(this, this.toString());
        thread.setDaemon(false);
        thread.start();
    }

    @Override
    public void run() {
        Debug.logInfo("Starting JMS Listener Factory Thread", MODULE);
        while (firstPass || connected < loadable) {
            if (Debug.verboseOn()) {
                Debug.logVerbose("First Pass: " + firstPass + " Connected: " + connected + " Available: " + loadable, MODULE);
            }
            this.loadListeners();
            if (loadable == 0) {
                // if there is nothing to do then we can break without sleeping
                break;
            }
            firstPass = false;
            try {
                Thread.sleep(20000);
            } catch (InterruptedException ie) {
                Debug.logError(ie, MODULE);
            }
            continue;
        }
        Debug.logInfo("JMS Listener Factory Thread Finished; All listeners connected.", MODULE);
    }

    // Load the JMS listeners
    private void loadListeners() {
        try {
            List<JmsService> jmsServices = ServiceConfigUtil.getServiceEngine().getJmsServices();

            if (Debug.verboseOn()) {
                Debug.logVerbose("Loading JMS Listeners.", MODULE);
            }
            for (JmsService service: jmsServices) {
                StringBuilder serverKey = new StringBuilder();
                for (Server server: service.getServers()) {
                    try {
                        if (server.getListen()) {
                            // create a server key
                            serverKey.append(server.getJndiServerName() + ":");
                            serverKey.append(server.getJndiName() + ":");
                            serverKey.append(server.getTopicQueue());
                            // store the server element
                            servers.put(serverKey.toString(), server);
                            // load the listener
                            GenericMessageListener listener = loadListener(serverKey.toString(), server);

                            // store the listener w/ the key
                            if (serverKey.length() > 0 && listener != null) {
                                listeners.put(serverKey.toString(), listener);
                            }
                        }
                    } catch (GenericServiceException gse) {
                        Debug.logInfo("Cannot load message listener " + serverKey + " error: (" + gse.toString() + ").", MODULE);
                    } catch (Exception e) {
                        Debug.logError(e, "Uncaught exception.", MODULE);
                    }
                }
            }
        } catch (GenericConfigException e) {
            Debug.logError(e, "Exception thrown while loading JMS listeners: ", MODULE);
        }
    }

    private GenericMessageListener loadListener(String serverKey, Server server) throws GenericServiceException {
        String serverName = server.getJndiServerName();
        String jndiName = server.getJndiName();
        String queueName = server.getTopicQueue();
        String type = server.getType();
        String userName = server.getUsername();
        String password = server.getPassword();
        String className = server.getListenerClass();

        if (UtilValidate.isEmpty(className)) {
            if ("topic".equals(type)) {
                className = JmsListenerFactory.TOPIC_LISTENER_CLASS;
            } else if ("queue".equals(type)) {
                className = JmsListenerFactory.QUEUE_LISTENER_CLASS;
            }
        }

        GenericMessageListener listener = listeners.get(serverKey);

        if (listener == null) {
            // Locked on the CLASS, not on this instance. The listener map is static and shared, so the lock
            // that guards a check-then-create against it has to be shared too: two factory instances - which
            // getInstance no longer produces, but the constructor is public and a caller may still use it -
            // would otherwise both pass the null check and both construct and connect a listener for the same
            // server key, leaving the loser's subscription live and this JVM holding two consumers.
            synchronized (LISTENER_CREATION_LOCK) {
                listener = listeners.get(serverKey);
                if (listener == null) {
                    ClassLoader cl = this.getClass().getClassLoader();

                    try {
                        Class<?> c = cl.loadClass(className);
                        Constructor<GenericMessageListener> cn = UtilGenerics.cast(c.getConstructor(Delegator.class,
                                String.class, String.class, String.class, String.class, String.class));

                        listener = cn.newInstance(delegator, serverName, jndiName, queueName, userName, password);
                    } catch (RuntimeException | NoSuchMethodException | InstantiationException | IllegalAccessException
                            | InvocationTargetException | ClassNotFoundException e) {
                        throw new GenericServiceException(e.getMessage(), e);
                    }
                    if (listener != null) {
                        listeners.put(serverKey, listener);
                    }
                    loadable++;
                }
            }

        }
        if (listener != null && !listener.isConnected()) {
            listener.load();
            if (listener.isConnected()) {
                connected++;
            }
        }
        return listener;
    }

    /**
     * Load a JMS message listener.
     * @param serverKey Name of the jms-service
     * @throws GenericServiceException
     */
    public void loadListener(String serverKey) throws GenericServiceException {
        Server server = servers.get(serverKey);

        if (server == null) {
            throw new GenericServiceException("No listener found with that serverKey.");
        }
        loadListener(serverKey, server);
    }

    /**
     * Close all the JMS message listeners.
     * @throws GenericServiceException
     */
    public void closeListeners() throws GenericServiceException {
        loadable = 0;
        for (String serverKey: listeners.keySet()) {
            closeListener(serverKey);
        }
    }

    /**
     * Close a JMS message listener.
     * @param serverKey Name of the jms-service
     * @throws GenericServiceException
     */
    public void closeListener(String serverKey) throws GenericServiceException {
        GenericMessageListener listener = listeners.get(serverKey);

        if (listener == null) {
            throw new GenericServiceException("No listener found with that serverKey.");
        }
        listener.close();
    }

    /**
     * Refresh a JMS message listener.
     * @param serverKey Name of the jms-service
     * @throws GenericServiceException
     */
    public void refreshListener(String serverKey) throws GenericServiceException {
        GenericMessageListener listener = listeners.get(serverKey);

        if (listener == null) {
            throw new GenericServiceException("No listener found with that serverKey.");
        }
        listener.refresh();
    }

    /**
     * Gets a Map of JMS Listeners.
     * @return Map of JMS Listeners
     */
    public Map<String, GenericMessageListener> getJMSListeners() {
        return UtilMisc.makeMapWritable(listeners);
    }

}
