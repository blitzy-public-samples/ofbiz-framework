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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.junit.jupiter.api.Test;

/**
 * The listener factory's singleton contract, which is what stops one JVM from subscribing twice.
 *
 * <p><strong>Why a test about a singleton is worth writing.</strong> Constructing this factory is not a
 * cheap bookkeeping act: its constructor starts a thread which loads every listener the service engine
 * declares, and each loaded listener SUBSCRIBES to its topic. An instance that is built and then discarded
 * therefore keeps its subscription - so a factory built twice makes the JVM a second consumer of the
 * entity-cache-invalidation topic, and every invalidation the fleet publishes is executed twice on that
 * instance, each execution a service invocation carrying a transaction. Measured on a two-instance fleet,
 * the broker showed three consumers instead of two.
 *
 * <p><strong>Hermetic.</strong> No broker is contacted and no configuration is loaded: in a plain JVM the
 * service configuration cannot be read at all - it needs the start-up configuration a running OFBiz has -
 * so the thread the constructor starts finds nothing to load and ends. That is exactly the condition this
 * test wants: the constructor's OBSERVABLE act is the thread it starts, and counting threads is how the
 * number of constructions is established without reaching into the class.
 */
public final class JmsListenerFactoryTests {

    /** How many callers arrive at once, standing in for the dispatchers a starting container creates. */
    private static final int CALLERS = 32;

    @Test
    public void oneJvmBuildsOneListenerFactoryHoweverManyCallersAskAtOnce() throws Exception {
        Delegator delegator = mock(Delegator.class);
        // Log once BEFORE measuring: the logging framework starts threads of its own on first use, and they
        // would otherwise be counted as constructions.
        Debug.logInfo("Asking " + CALLERS + " callers for the JMS listener factory at once", "test");
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        CountDownLatch ready = new CountDownLatch(CALLERS);
        CountDownLatch go = new CountDownLatch(1);
        List<JmsListenerFactory> answers = new ArrayList<>();
        List<Thread> callers = new ArrayList<>();
        for (int caller = 0; caller < CALLERS; caller++) {
            Thread asking = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                JmsListenerFactory answered = JmsListenerFactory.getInstance(delegator);
                synchronized (answers) {
                    answers.add(answered);
                }
            }, "asking-for-the-listener-factory-" + caller);
            callers.add(asking);
            asking.start();
        }
        // Every caller is parked at the latch, so their own threads are already counted.
        assertTrue(ready.await(30, TimeUnit.SECONDS), "the callers must all reach the starting line");
        long startedBefore = threads.getTotalStartedThreadCount();

        go.countDown();
        for (Thread asking : callers) {
            asking.join(TimeUnit.SECONDS.toMillis(30));
        }
        long startedAfter = threads.getTotalStartedThreadCount();

        assertEquals(CALLERS, answers.size(), "every caller must be answered");
        JmsListenerFactory singleton = answers.get(0);
        assertNotNull(singleton, "the factory must be built");
        for (JmsListenerFactory answered : answers) {
            assertSame(singleton, answered, "every caller must be given the same factory");
        }
        // THE assertion. Threads started while the callers raced is the number of factories CONSTRUCTED -
        // counted with getTotalStartedThreadCount so that a factory thread which has already finished is
        // still counted, which a live-thread census would miss. At most one, because a JVM that had already
        // built the factory before this test ran constructs none.
        long constructed = startedAfter - startedBefore;
        assertTrue(constructed <= 1, "one JVM must construct at most one listener factory, constructed "
                + constructed);
    }

    @Test
    public void readinessReportsNothingAboutAMessageBusThatIsNotConfigured() {
        // null is the "not applicable" answer the readiness probe reads: no listening JMS server is declared,
        // so nothing about this instance's fitness to serve follows from a broker. It is also what an
        // unreadable service configuration answers - the same fault stops any listener from being loaded, so
        // there is nothing to report - and a probe must never hold a fleet out of service over a question it
        // cannot answer.
        assertNull(JmsListenerFactory.readinessFailure(),
                "an undeclared message bus must be reported as no dependency at all");
    }
}
