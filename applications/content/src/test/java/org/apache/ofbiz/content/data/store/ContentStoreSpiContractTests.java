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
package org.apache.ofbiz.content.data.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;

/**
 * Shape contract of the {@link ContentStore} service provider interface.
 *
 * <p>{@code ContentStore} is the seam through which {@code DataResourceWorker} will reach a filesystem or an
 * S3-compatible object store instead of the local disk. Because it is an SPI, its VALUE is entirely in its
 * shape: every provider implements it, and the pre-existing call sites in {@code DataResourceWorker} keep
 * compiling only as long as the declared checked exceptions stay exactly what those call sites already catch.
 * Compilation alone proves the interface is legal Java; it does not protect any of that.
 *
 * <p>So this test pins the shape by reflection - the eight operations with their exact parameter and return
 * types, the exact declared exceptions in their declared order, and the deliberate absence of anything else.
 * Four drifts in particular would be invisible without it:
 * <ul>
 * <li>adding a checked exception, which would silently break every existing {@code DataResourceWorker} catch
 *     block that has to keep compiling unchanged;</li>
 * <li>turning an operation into a {@code default} method, which would let a provider inherit a silent no-op
 *     instead of being forced to implement storage;</li>
 * <li>letting a {@code File}, a {@code Path} or an object-store client type into a signature, which would
 *     couple callers to one backing store and defeat the whole point of the abstraction;</li>
 * <li>losing the known-length streaming {@code put} or the {@code size} probe, which are the only operations
 *     that let arbitrarily large content move through a provider without being held in the heap first, or
 *     losing {@code close}, without which a provider holding a network client leaks it on replacement.</li>
 * </ul>
 *
 * <p>Nothing here instantiates or mocks a {@code ContentStore}: a mock returning its own configured value
 * proves nothing about a provider. Provider BEHAVIOUR is covered separately by
 * {@link ContentStoreBehaviourContract}, executed against an implementation that performs real I/O.
 */
public final class ContentStoreSpiContractTests {

    private static final String SPI_PACKAGE = "org.apache.ofbiz.content.data.store";

    /**
     * The complete operation set as {@code returnType name(parameterTypes) throws exceptions}, listed in
     * method-name order for readability; the assertion sorts both sides, so this order carries no meaning.
     * Exception order within a single entry IS significant: {@link Method#getExceptionTypes()} preserves the
     * declared order, which is what pins {@code throws GeneralException, IOException} as written.
     */
    private static final List<String> EXPECTED_OPERATIONS = List.of(
            "void close() throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "void delete(java.lang.String) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "boolean exists(java.lang.String) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "byte[] get(java.lang.String) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "java.io.InputStream openStream(java.lang.String) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "void put(java.lang.String, byte[]) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "void put(java.lang.String, java.io.InputStream, long) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException",
            "long size(java.lang.String) throws org.apache.ofbiz.base.util.GeneralException, java.io.IOException");

    /** The number of operations {@link #EXPECTED_OPERATIONS} is expected to describe. */
    private static final int EXPECTED_OPERATION_COUNT = 8;

    /** The only types the SPI surface is allowed to mention, so no caller can be coupled to a backing store. */
    private static final Set<Class<?>> ALLOWED_SURFACE_TYPES =
            Set.of(void.class, boolean.class, long.class, String.class, byte[].class, InputStream.class);

    @Test
    public void theSpiDeclaresExactlyTheEightStorageOperations() {
        List<String> declared = declaredOperations().stream().map(ContentStoreSpiContractTests::describe).sorted().toList();
        List<String> expected = EXPECTED_OPERATIONS.stream().sorted().toList();

        // One assertion for the whole surface: a removed operation, an added one, a changed parameter or return
        // type and an altered throws clause are all caught by the same comparison.
        assertEquals(expected, declared, "the complete " + ContentStore.class.getSimpleName() + " operation set");
        // Guard the expectation itself: eight DISTINCT operations, so a copy-paste duplicate in the constant above
        // cannot mask a genuinely missing operation.
        assertEquals(EXPECTED_OPERATION_COUNT, expected.stream().distinct().count(),
                "the SPI must declare " + EXPECTED_OPERATION_COUNT + " distinct storage operations");
    }

    @Test
    public void everyOperationIsAbstractSoNoProviderCanInheritASilentNoOp() {
        for (Method operation : declaredOperations()) {
            assertTrue(Modifier.isAbstract(operation.getModifiers()), operation.getName() + " must stay abstract");
            assertFalse(operation.isDefault(),
                    operation.getName() + " must not be a default method: a provider would inherit a no-op instead of storing anything");
            assertFalse(Modifier.isStatic(operation.getModifiers()), operation.getName() + " must not be static");
        }
        // No static or private helper may hide in the SPI either - the declared set IS the whole interface.
        assertEquals(declaredOperations().size(), ContentStore.class.getDeclaredMethods().length,
                "the interface must declare nothing besides its " + EXPECTED_OPERATION_COUNT + " abstract operations");
    }

    @Test
    public void theSpiSurfaceIsBuiltOnlyFromPlainJavaTypes() {
        Set<Class<?>> surface = new LinkedHashSet<>();
        for (Method operation : declaredOperations()) {
            surface.add(operation.getReturnType());
            surface.addAll(Arrays.asList(operation.getParameterTypes()));
        }

        // Keys are opaque strings, payloads are plain bytes or a plain stream and lengths are plain longs, so
        // neither a local-file handle nor an object-store client type can appear. This is what keeps the contract below the service layer: no
        // java.io.File, no java.nio.file.Path, no software.amazon.* and no entity or service type.
        assertEquals(ALLOWED_SURFACE_TYPES, surface, "the union of every return and parameter type of the SPI");
        for (Class<?> type : surface) {
            String name = type.getName();
            assertFalse(name.startsWith("software.amazon"), "no object-store client type may appear in the SPI: " + name);
            assertFalse(name.startsWith("org.apache.ofbiz"), "no OFBiz framework, entity or service type may appear in the SPI: " + name);
        }
    }

    @Test
    public void theSpiIsAPlainStatelessInterfaceInTheContentComponent() {
        assertTrue(ContentStore.class.isInterface(), "ContentStore must be an interface");
        assertTrue(Modifier.isPublic(ContentStore.class.getModifiers()), "ContentStore must be public");
        assertEquals(SPI_PACKAGE, ContentStore.class.getPackageName(),
                "the SPI must stay beside its only integration point in the content component");

        // An SPI with state, a supertype, a type parameter or a nested type would stop being a drop-in contract.
        assertEquals(List.of(), Arrays.stream(ContentStore.class.getDeclaredFields()).map(field -> field.getName()).toList(),
                "the SPI must declare no fields or constants");
        assertEquals(List.of(), Arrays.stream(ContentStore.class.getInterfaces()).map(Class::getName).toList(),
                "the SPI must extend nothing");
        assertEquals(0, ContentStore.class.getTypeParameters().length, "the SPI must not be generic");
        assertEquals(List.of(), Arrays.stream(ContentStore.class.getDeclaredClasses()).map(Class::getName).toList(),
                "the SPI must declare no nested types");
    }

    @Test
    public void theDocumentedAbsentKeySignalFitsInsideTheDeclaredExceptionSet() {
        // get(), openStream() and size() are documented to throw FileNotFoundException for an absent key rather
        // than returning null or a sentinel. That is only possible without widening the throws clause because
        // FileNotFoundException IS an IOException - which is why the declared set could be held to exactly two
        // types even as the SPI grew the streaming, measuring and lifecycle operations.
        assertTrue(IOException.class.isAssignableFrom(FileNotFoundException.class),
                "the documented absent-key signal must be expressible under the declared IOException");
        for (Method operation : declaredOperations()) {
            assertEquals(List.of(GeneralException.class.getName(), IOException.class.getName()),
                    Arrays.stream(operation.getExceptionTypes()).map(Class::getName).toList(),
                    operation.getName() + " must declare exactly the two exceptions the existing call sites already catch");
        }
        // Both are checked, so a caller cannot silently ignore either one.
        assertFalse(RuntimeException.class.isAssignableFrom(GeneralException.class), "GeneralException must stay a checked exception");
        assertFalse(RuntimeException.class.isAssignableFrom(IOException.class), "IOException must stay a checked exception");
    }

    /** Every non-synthetic method the SPI declares. */
    private static List<Method> declaredOperations() {
        List<Method> operations = new ArrayList<>();
        for (Method method : ContentStore.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                operations.add(method);
            }
        }
        return operations;
    }

    private static String describe(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes()).map(Class::getCanonicalName).collect(Collectors.joining(", "));
        String exceptions = Arrays.stream(method.getExceptionTypes()).map(Class::getCanonicalName).collect(Collectors.joining(", "));
        return method.getReturnType().getCanonicalName() + " " + method.getName() + "(" + parameters + ") throws " + exceptions;
    }
}
