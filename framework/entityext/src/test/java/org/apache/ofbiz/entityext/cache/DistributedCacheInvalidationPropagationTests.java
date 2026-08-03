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
package org.apache.ofbiz.entityext.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntity;
import org.apache.ofbiz.entity.GenericPK;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.cache.Cache;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelField;
import org.apache.ofbiz.entity.model.ModelFieldType;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Proves that an entity-cache invalidation raised on one instance is published, routed and APPLIED on a
 * peer instance - the delivery evidence the multi-instance objective rests on.
 *
 * <p><b>Why this test exists.</b> Enabling {@code distributed-cache-clear-enabled} is only useful if an
 * invalidation actually reaches the other instances. Everything that surrounds that claim can be true while
 * the claim itself is false: a delegator can carry the attribute, a container entry point can prove a broker
 * answers on its port, and a readiness probe can report that a JMS listener is connected, and an invalidation
 * can still fail to arrive because the publisher names a service the consumer does not implement, or carries a
 * payload the consumer cannot read, or the consumer applies it in a way that re-publishes it forever. Those
 * are properties of the code on both ends of the hop, so they are tested here rather than asserted in a
 * comment or delegated to an operator running two containers by hand.
 *
 * <p><b>What is real and what is not.</b> Both ends are the production implementation:
 * {@link EntityCacheServices#distributedClearCacheLine} and friends publish, and
 * {@link EntityCacheServices#clearCacheLine} and {@link EntityCacheServices#clearAllEntityCaches} consume. The
 * routing between them is read out of the shipped {@code framework/entityext/servicedef/services.xml}, so the
 * service a publisher names and the implementation a consumer runs are the ones the deployment actually
 * declares. The peer's cache is a real {@link Cache}. What is NOT real is the broker hop itself: the message
 * is carried from publisher to consumer by this test instead of by a JMS provider, because
 * {@code dependencies.gradle} bundles only the JMS API - the plan authorises exactly two dependency
 * additions, neither a broker client nor an embedded broker - so no provider exists to publish through in a
 * unit test. Substituting the transport is what makes this runnable offline on every build; everything it
 * would have carried is asserted byte for byte on the way past.
 *
 * <p><b>What the operator procedure adds.</b> {@code DOCKER.adoc} still carries a two-instance procedure, and
 * it is still worth running, because it exercises the one thing this cannot: a specific broker, with a
 * specific client library, specific credentials and a specific topic. That is a deployment property. This
 * test covers the code property - that what one instance publishes is what another instance consumes and
 * applies - and it is the required gate, because it fails the build rather than waiting for someone to
 * remember.
 */
public final class DistributedCacheInvalidationPropagationTests {

    /** The shipped service definitions that route a published invalidation to its consumer. */
    private static final String SERVICE_DEFINITIONS = "framework/entityext/servicedef/services.xml";

    /** The entity used throughout: cached, with a single-field primary key, like most reference data. */
    private static final String ENTITY_NAME = "TestingType";

    private static final String PK_FIELD = "testingTypeId";
    private static final String PK_VALUE = "PROPAGATION-1";

    /** The user the publisher authenticates the distributed clear as. */
    private static final String PUBLISHER_USER_LOGIN_ID = "system";

    private Cache peerCache;
    private Delegator peerDelegator;
    /**
     * The delegator a mutated value carries. A {@link GenericValue} records the delegator it was made on,
     * so the row invalidated here is made on the PUBLISHING instance and then travels to the peer - which is
     * what happens on the wire, where the serialised value carries its origin's delegator name with it.
     */
    private Delegator publishingInstance;
    private DispatchContext peerDispatchContext;
    private ModelEntity modelEntity;

    @BeforeEach
    public void setUp() throws Exception {
        modelEntity = testingTypeModel();
        publishingInstance = mock(Delegator.class);
        when(publishingInstance.getDelegatorName()).thenReturn("publisher-instance");
        // Built into a local first, never inline: stubbing the field type while a when(...) for the
        // delegator is still open is nested stubbing, which Mockito rejects.
        ModelFieldType idType = stringFieldType();
        when(publishingInstance.getEntityFieldType(any(ModelEntity.class), anyString())).thenReturn(idType);
        peerCache = new Cache("peer-instance");
        peerDelegator = peerDelegator(peerCache);
        peerDispatchContext = mock(DispatchContext.class);
        when(peerDispatchContext.getDelegator()).thenReturn(peerDelegator);
    }

    @Test
    public void anInvalidationRaisedOnOneInstanceIsConsumedAndAppliedByItsPeer() throws Exception {
        // The peer is holding the row in its entity cache. This is the state that makes distributed
        // clearing necessary at all: without it the peer keeps serving this value after another instance
        // has changed it, which is the incoherence the objective exists to remove.
        GenericValue value = testingType("Before the update");
        peerCache.put(value);
        assertNotNull(peerCache.get(value.getPrimaryKey()), "the peer must be holding the row before the"
                + " invalidation, otherwise this test proves nothing about removing it");

        // PUBLISH. The real publisher, driven exactly as GenericDelegator drives it when a mutation is
        // made with distribute=true.
        RecordedTopic topic = new RecordedTopic();
        publisherFor(topic).distributedClearCacheLine(value);

        // The message that went onto the topic, asserted before it is delivered: a publisher that named
        // another service, or omitted the value, would be a silent failure at the far end.
        assertEquals(1, topic.published().size(), "exactly one invalidation must be published for one"
                + " mutation, found " + topic.published());
        Published message = topic.published().get(0);
        assertEquals("distributedClearCacheLineByValue", message.serviceName(),
                "the publisher must name the JMS service the consumer implements");
        assertEquals(value, message.payload().get("value"), "the published payload must carry the value that"
                + " was invalidated, or the consumer cannot know what to remove");
        assertNotNull(message.payload().get("userLogin"), "the published payload must carry the login the"
                + " consuming service authenticates, because that service is auth=true");

        // ROUTE. Read from the shipped descriptor, not restated: this is the mapping the JMS listener uses
        // to decide what to run on the consuming instance.
        assertEquals("clearCacheLineByValue", jmsServiceInvokes(message.serviceName()),
                "the shipped service definition must route the published service to a consumer");
        assertEquals("org.apache.ofbiz.entityext.cache.EntityCacheServices",
                exportedServiceLocation("clearCacheLineByValue"), "the consumer must be the class this test"
                        + " drives below, otherwise the code exercised here is not the code deployed");

        // CONSUME AND APPLY. The real consumer, given the real payload, running against the peer's own
        // delegator - which is what the JMS listener does on the receiving instance.
        Map<String, Object> delivered = new LinkedHashMap<>(message.payload());
        EntityCacheServices.clearCacheLine(peerDispatchContext, delivered);

        // The peer applied it: its delegator was asked to clear exactly the row that was invalidated, and
        // its cache no longer holds it. Both are asserted, because they are different claims - the first is
        // the contract the consumer invokes, the second is the effect a request on this instance will now
        // see.
        verify(peerDelegator).clearCacheLine(value, false);
        assertNull(peerCache.get(value.getPrimaryKey()), "the peer's cache must no longer hold the row an"
                + " invalidation was delivered for; that is what delivery means");
    }

    @Test
    public void aDeliveredInvalidationIsNotRePublishedByTheInstanceThatConsumesIt() throws Exception {
        // The property that makes distributed clearing safe rather than catastrophic. Every instance
        // subscribes to the same topic, so if consuming an invalidation republished it, one mutation would
        // produce a message per instance, each producing another, without bound - a broker storm that takes
        // the fleet down rather than keeping its caches coherent. The consuming service therefore has to
        // apply the clear LOCALLY, with distribute false.
        GenericValue value = testingType("Before the update");
        peerCache.put(value);
        RecordedTopic topic = new RecordedTopic();
        publisherFor(topic).distributedClearCacheLine(value);
        Map<String, Object> delivered = new LinkedHashMap<>(topic.published().get(0).payload());

        // Delivered exactly as published. In particular 'distribute' is ABSENT, which is the case that
        // matters: the consuming service treats an absent flag as false, so a publisher that never sets it
        // cannot cause a re-publish.
        assertFalse(delivered.containsKey("distribute"), "the published payload must not ask the consumer to"
                + " redistribute, and this one carries: " + delivered.keySet());
        EntityCacheServices.clearCacheLine(peerDispatchContext, delivered);

        verify(peerDelegator).clearCacheLine(value, false);
        verify(peerDelegator, never()).clearCacheLine(any(GenericValue.class), eq(true));
    }

    @Test
    public void everyKindOfInvalidationTheEngineRaisesIsPublishedAndRoutedToAConsumer() throws Exception {
        // The engine raises five kinds of invalidation, and the fleet is only coherent if all five arrive.
        // One kind that publishes to a service no consumer implements would leave a specific class of stale
        // read - a by-condition cache entry, say - with nothing to show it, because the other four would
        // keep working.
        GenericValue value = testingType("Before the update");
        GenericPK primaryKey = value.getPrimaryKey();
        GenericEntity dummyPK = GenericEntity.createGenericEntity(publishingInstance, modelEntity,
                Map.of(PK_FIELD, (Object) PK_VALUE));
        EntityCondition condition = EntityCondition.makeCondition(PK_FIELD, PK_VALUE);

        Map<String, Runnable> raised = new LinkedHashMap<>();
        RecordedTopic topic = new RecordedTopic();
        EntityCacheServices publisher = publisherFor(topic);
        raised.put("distributedClearCacheLineByValue", () -> publisher.distributedClearCacheLine(value));
        raised.put("distributedClearCacheLineByPrimaryKey", () -> publisher.distributedClearCacheLine(primaryKey));
        raised.put("distributedClearCacheLineByDummyPK", () -> publisher.distributedClearCacheLineFlexible(dummyPK));
        raised.put("distributedClearCacheLineByCondition", () -> publisher
                .distributedClearCacheLineByCondition(ENTITY_NAME, condition));
        raised.put("distributedClearAllEntityCaches", publisher::clearAllCaches);

        for (Map.Entry<String, Runnable> kind : raised.entrySet()) {
            topic.reset();
            kind.getValue().run();

            assertEquals(1, topic.published().size(), kind.getKey() + " must publish exactly one message");
            Published message = topic.published().get(0);
            assertEquals(kind.getKey(), message.serviceName(), "the publisher must name the expected service");
            String consumer = jmsServiceInvokes(message.serviceName());
            assertNotNull(consumer, message.serviceName() + " must be declared as a JMS service in "
                    + SERVICE_DEFINITIONS);
            assertEquals("org.apache.ofbiz.entityext.cache.EntityCacheServices",
                    exportedServiceLocation(consumer), consumer + " must be implemented by EntityCacheServices"
                            + " so a peer has something to run when this message arrives");
            assertNotNull(message.payload().get("userLogin"), kind.getKey() + " must carry the login its"
                    + " auth=true consumer authenticates");
        }
    }

    @Test
    public void aClearAllDeliveredToAPeerEmptiesThatPeersCache() throws Exception {
        // The coarse invalidation, end to end, because it is the one an operator reaches for after a bulk
        // data load - the moment when every instance is holding the most stale data it ever holds.
        peerCache.put(testingType("Before the update"));
        assertNotNull(peerCache.get(testingType("Before the update").getPrimaryKey()),
                "the peer must be holding something for emptying its cache to be observable");
        RecordedTopic topic = new RecordedTopic();

        publisherFor(topic).clearAllCaches();

        Published message = topic.published().get(0);
        assertEquals("distributedClearAllEntityCaches", message.serviceName(), "the published service");
        assertEquals("clearAllEntityCaches", jmsServiceInvokes(message.serviceName()), "the routed consumer");
        EntityCacheServices.clearAllEntityCaches(peerDispatchContext, new LinkedHashMap<>(message.payload()));

        verify(peerDelegator).clearAllCaches(false);
        assertNull(peerCache.get(testingType("Before the update").getPrimaryKey()),
                "a delivered clear-all must leave the peer's cache holding nothing");
    }

    @Test
    public void nothingIsPublishedWhenThePublisherCannotAuthenticateTheClear() throws Exception {
        // The consuming services are auth=true, so a message with no login is a message the peer will
        // refuse. Publishing one anyway would put an invalidation on the topic that silently does nothing on
        // every instance that receives it - the fleet would look coherent and be stale. Refusing to publish
        // at least leaves the failure where it can be seen, in the publisher's own log.
        RecordedTopic topic = new RecordedTopic();
        EntityCacheServices publisher = new EntityCacheServices();
        Delegator publisherDelegator = mock(Delegator.class);
        when(publisherDelegator.getDelegatorName()).thenReturn("publisher-instance");
        // Delegator extends DelegatorProvider, so EntityQuery.use(delegator) binds to the provider overload
        // and asks the delegator for itself. A mock answers null there unless told otherwise, which would
        // leave the publisher's own login lookup querying nothing at all.
        when(publisherDelegator.getDelegator()).thenReturn(publisherDelegator);
        ModelFieldType idType = stringFieldType();
        when(publisherDelegator.getEntityFieldType(any(ModelEntity.class), anyString())).thenReturn(idType);
        ModelEntity userLoginModel = singleFieldModel("UserLogin", "userLoginId");
        when(publisherDelegator.getModelEntity("UserLogin")).thenReturn(userLoginModel);
        // No UserLogin row for the configured id - what a delegator answers before seed data is loaded, or
        // when the configured id does not exist. findList is left returning nothing.
        when(publisherDelegator.findList(eq("UserLogin"), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of());
        installDispatcher(publisher, publisherDelegator, topic);

        publisher.distributedClearCacheLine(testingType("Before the update"));

        assertEquals(List.of(), topic.published(), "an invalidation that cannot be authenticated must not be"
                + " published, because every peer would refuse it and none would report the refusal to the"
                + " instance that raised it");
    }

    /*
     * Publisher wiring
     */

    /**
     * Builds the production publisher, wired to a recording topic instead of a broker.
     *
     * <p>The publisher is the real {@link EntityCacheServices}. Only its dispatcher is substituted, and only
     * at the one point where a message would leave this JVM: {@code runAsync} on the JMS-backed service. That
     * is the transport boundary, so substituting it there and nowhere else is what keeps every decision the
     * publisher makes - which service, which payload, whether to publish at all - inside the code under test.
     *
     * @param topic where published messages are recorded
     * @return the publisher, ready to raise invalidations
     * @throws Exception if the publisher cannot be wired, which would mean it had changed shape
     */
    private EntityCacheServices publisherFor(RecordedTopic topic) throws Exception {
        EntityCacheServices publisher = new EntityCacheServices();
        Delegator publisherDelegator = mock(Delegator.class);
        when(publisherDelegator.getDelegatorName()).thenReturn("publisher-instance");
        // Delegator extends DelegatorProvider, so EntityQuery.use(delegator) binds to the provider overload
        // and asks the delegator for itself. A mock answers null there unless told otherwise, which would
        // leave the publisher's own login lookup querying nothing at all.
        when(publisherDelegator.getDelegator()).thenReturn(publisherDelegator);
        ModelFieldType idType = stringFieldType();
        when(publisherDelegator.getEntityFieldType(any(ModelEntity.class), anyString())).thenReturn(idType);
        installDispatcher(publisher, publisherDelegator, topic);
        installAuthenticatedUser(publisher);
        return publisher;
    }

    /**
     * Installs the substituted dispatcher and the publishing instance's delegator.
     *
     * <p>Assigned reflectively because {@code setDelegator} also creates a real {@link LocalDispatcher} from
     * the service container, which needs a loaded component registry that a unit test does not have. The
     * fields are the ones {@code setDelegator} would have set, so the publisher is left in exactly the state
     * a started instance leaves it in.
     *
     * @param publisher the publisher to wire
     * @param delegator the publishing instance's delegator
     * @param topic where published messages are recorded
     * @throws Exception if the fields cannot be set, which would mean the class had changed shape
     */
    private void installDispatcher(EntityCacheServices publisher, Delegator delegator, RecordedTopic topic)
            throws Exception {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        doAnswer(invocation -> {
            topic.record(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(dispatcher).runAsync(anyString(), any(), anyBoolean());
        set(publisher, "delegator", delegator);
        set(publisher, "dispatcher", dispatcher);
        set(publisher, "userLoginId", PUBLISHER_USER_LOGIN_ID);
    }

    /**
     * Makes the publisher's delegator answer with a {@code UserLogin}, as a started instance's does.
     *
     * <p>The publisher looks the login up through {@code EntityQuery.use(delegator).from("UserLogin")...},
     * so the delegator's own query path is what has to answer. Stubbed deeply for that reason rather than
     * by substituting the lookup, which would take the auth decision out of the code under test.
     *
     * @param publisher the publisher whose delegator is to answer
     * @throws Exception if the delegator cannot be read back
     */
    private void installAuthenticatedUser(EntityCacheServices publisher) throws Exception {
        Delegator delegator = (Delegator) get(publisher, "delegator");
        ModelEntity userLoginModel = singleFieldModel("UserLogin", "userLoginId");
        when(delegator.getModelEntity("UserLogin")).thenReturn(userLoginModel);
        GenericValue userLogin = GenericValue.create(delegator, userLoginModel,
                Map.of("userLoginId", (Object) PUBLISHER_USER_LOGIN_ID));
        // findList is the method EntityQuery ultimately calls, so stubbing it is what makes the publisher's
        // own lookup - EntityQuery.use(delegator).from("UserLogin")...cache().queryOne() - answer. Stubbed
        // there rather than at the lookup, so the decision whether to publish stays inside the publisher.
        when(delegator.findList(eq("UserLogin"), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(userLogin));
    }

    /**
     * The field-type answer a delegator gives for the fixtures' fields.
     *
     * <p>{@code GenericEntity.set} resolves each field's abstract type through the delegator, because the
     * concrete Java type depends on the datasource dialect. There is no datasource here, so the resolution is
     * answered directly: every field of these fixtures is an {@code id}, which every shipped dialect maps to
     * a String.
     *
     * @return the field type to answer with
     */
    private static ModelFieldType stringFieldType() {
        ModelFieldType type = mock(ModelFieldType.class);
        when(type.getJavaType()).thenReturn("java.lang.String");
        return type;
    }

    /*
     * Peer wiring
     */

    /**
     * Builds the peer instance's delegator, whose cache operations act on a real {@link Cache}.
     *
     * <p>The two stubbed methods are the two the consuming services call, and each does what
     * {@code GenericDelegator} does for a locally-applied clear: it removes from the instance's cache and
     * does not redistribute. They are stubbed rather than obtained from a real {@code GenericDelegator}
     * because constructing one needs a datasource, an entity model and a loaded component registry, which
     * would make this an integration test and take it out of the required build gate. The delivery claim does
     * not rest on the stub: {@code verify(peerDelegator).clearCacheLine(value, false)} asserts the contract
     * the consumer invoked, and the real {@link Cache} assertion beside it shows the effect - the stub only
     * connects the two, and it is the one line {@code GenericDelegator} has at that point.
     *
     * @param cache the peer's entity cache
     * @return the peer's delegator
     */
    private Delegator peerDelegator(Cache cache) {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegatorName()).thenReturn("peer-instance");
        doAnswer(invocation -> {
            cache.remove((GenericEntity) invocation.getArgument(0));
            return null;
        }).when(delegator).clearCacheLine(any(GenericValue.class), anyBoolean());
        doAnswer(invocation -> {
            cache.clear();
            return null;
        }).when(delegator).clearAllCaches(anyBoolean());
        return delegator;
    }

    /*
     * Fixtures
     */

    /**
     * Builds the cached row both instances hold.
     *
     * @param description the non-key field's value, so a stale copy is distinguishable from a fresh one
     * @return the value
     */
    private GenericValue testingType(String description) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(PK_FIELD, PK_VALUE);
        fields.put("description", description);
        return GenericValue.create(publishingInstance, modelEntity, fields);
    }

    /**
     * Builds the entity model for the cached row: a single-field key plus a description, cached.
     *
     * @return the model
     */
    private ModelEntity testingTypeModel() {
        ModelEntity model = singleFieldModel(ENTITY_NAME, PK_FIELD);
        model.addField(field(model, "description", false));
        return model;
    }

    /**
     * Builds a minimal cacheable entity model with one primary-key field.
     *
     * <p>Built here rather than read from an entity definition so the test needs no entity engine, no
     * datasource and no component registry, and so the model cannot change under it.
     *
     * @param entityName the entity's name
     * @param keyField the single primary-key field's name
     * @return the model
     */
    private ModelEntity singleFieldModel(String entityName, String keyField) {
        ModelEntity model = new ModelEntity();
        model.setEntityName(entityName);
        model.setTableName(entityName.toUpperCase(java.util.Locale.ROOT));
        model.setPackageName("org.apache.ofbiz.entityext.test");
        // Explicit, because both are what make the entity take part in cache invalidation at all: a
        // never-cached entity is not cached, and an entity that does not auto-clear is not evicted.
        model.setNeverCache(false);
        model.setAutoClearCache(true);
        model.addField(field(model, keyField, true));
        return model;
    }

    /**
     * Builds one model field through the engine's own factory, which is the only way to make one: a
     * {@link ModelField} is immutable and has no public constructor.
     *
     * @param owner the entity the field belongs to
     * @param name the field name
     * @param primaryKey whether the field is part of the primary key
     * @return the field
     */
    private ModelField field(ModelEntity owner, String name, boolean primaryKey) {
        return ModelField.create(owner, name, "id", primaryKey);
    }

    /*
     * Shipped service-definition access
     */

    /**
     * Returns the service a JMS-engine service tells a listener to invoke on the consuming instance.
     *
     * @param jmsServiceName the service a publisher names
     * @return the service name the consumer runs, or null when no such JMS service is declared
     * @throws Exception if the descriptor cannot be read
     */
    private String jmsServiceInvokes(String jmsServiceName) throws Exception {
        for (Element service : serviceElements()) {
            if (jmsServiceName.equals(service.getAttribute("name")) && "jms".equals(service.getAttribute("engine"))) {
                assertEquals("serviceMessenger", service.getAttribute("location"), jmsServiceName + " must be"
                        + " published on the serviceMessenger topic, which is the transport the deployment"
                        + " configures");
                return service.getAttribute("invoke");
            }
        }
        return null;
    }

    /**
     * Returns the implementation class of an exported java service, so the consumer this test drives can be
     * shown to be the consumer a deployment runs.
     *
     * @param serviceName the consuming service's name
     * @return its location attribute, or null when it is not declared as an exported java service
     * @throws Exception if the descriptor cannot be read
     */
    private String exportedServiceLocation(String serviceName) throws Exception {
        for (Element service : serviceElements()) {
            if (serviceName.equals(service.getAttribute("name")) && "java".equals(service.getAttribute("engine"))) {
                assertEquals("true", service.getAttribute("export"), serviceName + " must be exported, because a"
                        + " JMS listener on a peer reaches it from outside that instance");
                assertEquals("true", service.getAttribute("auth"), serviceName + " must require authentication,"
                        + " because it is reachable by anything that can publish to the topic");
                return service.getAttribute("location");
            }
        }
        return null;
    }

    /**
     * Parses the shipped service definitions.
     *
     * <p>Secure processing on and external DTD loading off, so no network access takes place and the
     * document is read structurally.
     *
     * @return every {@code service} element declared
     * @throws Exception if the descriptor is missing or cannot be parsed
     */
    private List<Element> serviceElements() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Path descriptor = repositoryRoot().resolve(SERVICE_DEFINITIONS);
        assertTrue(Files.isRegularFile(descriptor), "missing authoritative descriptor " + descriptor);
        Document document = factory.newDocumentBuilder().parse(descriptor.toFile());
        List<Element> services = new ArrayList<>();
        NodeList nodes = document.getDocumentElement().getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && "service".equals(node.getNodeName())) {
                services.add((Element) node);
            }
        }
        assertFalse(services.isEmpty(), "no service is declared in " + descriptor);
        return services;
    }

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }

    /*
     * Reflective field access, so the production class needs no test-only setter
     */

    private static void set(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /** One message as it went onto the topic: the service a peer will run, and the payload it will read. */
    private record Published(String serviceName, Map<String, Object> payload) { }

    /** The topic, standing in for the broker: it records what was published instead of transporting it. */
    private static final class RecordedTopic {

        private final List<Published> published = new ArrayList<>();

        private void record(String serviceName, Map<String, Object> payload) {
            published.add(new Published(serviceName, new LinkedHashMap<>(payload)));
        }

        private List<Published> published() {
            return List.copyOf(published);
        }

        private void reset() {
            published.clear();
        }
    }
}
