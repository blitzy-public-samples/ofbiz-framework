/*
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
 */
package org.apache.ofbiz.content.data.store;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityFieldMap;

/**
 * The fixtures the content-store tests share: a stand-in delegator, a stubbed {@code DataResource} row, and
 * access to the worker's private read seam.
 *
 * <p>Held in one place because two suites need them and they must agree. {@link ContentStoreFactoryTest} runs
 * entirely offline against a mocked SDK client, and {@link ObjectStoreIntegrationTests} runs the same seam
 * against a real object store; if each built its own delegator or its own row, the offline suite could pass on
 * a fixture the live one never produces - which is precisely the gap that makes an offline suite reassuring
 * rather than informative.
 *
 * <p>Nothing here reaches a network, a database or the filesystem. The delegator is a mock whose
 * {@code SystemProperty} lookups are answered from a map, which is what lets a test state what a
 * per-deployment override holds without an entity engine behind it.
 */
final class ContentStoreTestSupport {

    /** The resource the provider configuration is read from; never {@code content.properties}. */
    static final String RESOURCE = "content";

    private ContentStoreTestSupport() {
    }

    /**
     * Builds the delegator the store key and the property lookups are resolved through.
     *
     * @param base the delegator base name
     * @param tenantId the tenancy, or null for the base tenancy
     * @return a delegator that answers only what this package reads from one
     */
    static Delegator seamDelegator(String base, String tenantId) {
        return seamDelegator(base, tenantId, Map.of());
    }

    /**
     * Builds the delegator, with {@code SystemProperty} rows the entity engine would otherwise be queried for.
     *
     * @param base the delegator base name
     * @param tenantId the tenancy, or null for the base tenancy
     * @param systemProperties the per-deployment overrides the database is to hold, by property name
     * @return a delegator that answers only what this package reads from one
     */
    static Delegator seamDelegator(String base, String tenantId, Map<String, String> systemProperties) {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegatorName()).thenReturn(tenantId == null ? base : base + "#" + tenantId);
        when(delegator.getDelegatorBaseName()).thenReturn(base);
        when(delegator.getDelegatorTenantId()).thenReturn(tenantId);
        when(delegator.getDelegator()).thenReturn(delegator);
        Map<String, GenericValue> rows = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : systemProperties.entrySet()) {
            GenericValue held = mock(GenericValue.class);
            when(held.getString("systemPropertyValue")).thenReturn(row.getValue());
            rows.put(row.getKey(), held);
        }
        try {
            when(delegator.findList(anyString(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> systemPropertyRow(invocation.getArgument(1), rows));
        } catch (GenericEntityException stubbing) {
            // Declared by the interface method, unreachable on a mock: nothing queries a database here.
            throw new IllegalStateException("a mock cannot raise the interface's checked exception", stubbing);
        }
        return delegator;
    }

    /**
     * Answers a {@code SystemProperty} lookup from a set of rows.
     *
     * @param condition the condition the query built, which carries the resource and the property name
     * @param rows the rows the database is to hold, by property name
     * @return the single row the lookup finds, or an empty list when the database holds none
     */
    private static List<GenericValue> systemPropertyRow(Object condition, Map<String, GenericValue> rows) {
        if (!(condition instanceof EntityFieldMap lookup)) {
            return Collections.emptyList();
        }
        Object resource = lookup.getField("systemResourceId");
        Object property = lookup.getField("systemPropertyId");
        if (!RESOURCE.equals(resource) || property == null) {
            return Collections.emptyList();
        }
        GenericValue held = rows.get(property.toString());
        return held == null ? Collections.emptyList() : List.of(held);
    }

    /**
     * Builds the {@code DataResource} row the read and write seams see, stubbed only as far as they reach.
     *
     * @param typeId the {@code dataResourceTypeId}
     * @param objectInfo the recorded location
     * @param dataResourceId the immutable identifier
     * @param delegator the delegator the row was read through
     * @return the stubbed row
     */
    static GenericValue fileResource(String typeId, String objectInfo, String dataResourceId,
            Delegator delegator) {
        GenericValue dataResource = mock(GenericValue.class);
        when(dataResource.getString("dataResourceTypeId")).thenReturn(typeId);
        when(dataResource.getString("dataResourceId")).thenReturn(dataResourceId);
        when(dataResource.getString("objectInfo")).thenReturn(objectInfo);
        // The binary write services read the same three fields through get() rather than getString(), so a
        // row stubbed for one accessor alone would hand them nulls and prove nothing about their behaviour.
        when(dataResource.get("dataResourceTypeId")).thenReturn(typeId);
        when(dataResource.get("dataResourceId")).thenReturn(dataResourceId);
        when(dataResource.get("objectInfo")).thenReturn(objectInfo);
        when(dataResource.getDelegator()).thenReturn(delegator);
        return dataResource;
    }

    /**
     * Renders through the worker's private read seam.
     *
     * <p>Reflection rather than a widened signature: the seam is deliberately not API, and making it visible
     * for a test would change the very surface the refactor undertook not to change.
     *
     * @param typeId the {@code dataResourceTypeId}
     * @param objectInfo the recorded location
     * @param rootDir the context root the caller supplies, or null
     * @param delegator the delegator the configuration is resolved through
     * @param dataResourceId the immutable identifier the store key is derived from
     * @return what the seam rendered
     * @throws Exception whatever the seam threw, unwrapped from the reflective call
     */
    static String renderedThroughSeam(String typeId, String objectInfo, String rootDir, Delegator delegator,
            String dataResourceId) throws Exception {
        Method seam = DataResourceWorker.class.getDeclaredMethod("renderFile", String.class, String.class,
                String.class, Appendable.class, Delegator.class, String.class);
        seam.setAccessible(true);
        StringBuilder out = new StringBuilder();
        try {
            seam.invoke(null, typeId, objectInfo, rootDir, out, delegator, dataResourceId);
        } catch (InvocationTargetException reflected) {
            throw (Exception) reflected.getCause();
        }
        return out.toString();
    }
}
