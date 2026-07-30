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

import org.apache.ofbiz.base.util.GeneralException;

/**
 * Raised when the content storage configuration names a backend that cannot be honoured.
 *
 * <p>This is the fail-closed signal of the {@code store} package. It is thrown by
 * {@link ContentStoreFactory} when {@code content.store.provider} names a provider that does not exist,
 * or names one that exists but cannot be constructed. It is deliberately <em>not</em> thrown for an
 * absent, blank or {@code database} value: those three states are the documented way to select the
 * pre-existing {@code DataResource} database storage, and an unmodified checkout must keep working
 * untouched.
 *
 * <p><strong>Why this refuses instead of falling back.</strong> The alternative to raising it is to
 * fall back to database storage, and that fallback is the defect it exists to prevent: a deployment
 * that asked for object storage and silently got database storage has its content in a backend nobody
 * chose, outside that store's retention, encryption and access controls, and nowhere anyone will look
 * for it - with no error raised anywhere. A refused content operation is loud, immediate and
 * reversible; content written to an unintended backend is none of those.
 *
 * <p><strong>Why it extends {@link GeneralException}.</strong> Every storage operation of this package
 * already reports failure as a {@code GeneralException}, so a configuration refusal that is one too
 * needs no separate handling anywhere in the delegation seam, and a caller that catches the storage
 * failure type cannot accidentally let a refusal through unhandled. The specific type stays available
 * for the callers - and the tests - that want to distinguish "this deployment is misconfigured" from
 * "this one operation failed".
 *
 * <p>Its message never reproduces attacker-influenced text as itself: a configured value reaches it
 * only through {@code ContentStoreUtil.describe}, which echoes a value only while it is short and free
 * of control characters and otherwise substitutes an opaque reference, so a value carrying a line
 * break cannot forge a log record through this exception.
 */
public class ContentStoreConfigurationException extends GeneralException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message that explains what was configured and what was expected.
     *
     * @param message the explanation, which must already be safe to log
     */
    public ContentStoreConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates the exception around the underlying failure that stopped the requested provider from
     * being built.
     *
     * @param message the explanation, which must already be safe to log
     * @param cause the failure that prevented the requested provider from being constructed, retained
     *     so the real reason - typically an absent client library - stays available
     */
    public ContentStoreConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
