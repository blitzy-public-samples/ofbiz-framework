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
package org.apache.ofbiz.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The disposition decision the content-streaming route applies to USER-SUPPLIED content.
 *
 * <p>Pure decisions, tested on their own: what the responses of {@code /content/control/img} declare is
 * what keeps an uploaded file from acting inside the application's own origin, and it has to hold for a
 * recorded MIME type that carries parameters, for a name that carries header syntax, and for the absence
 * of either. The transfer itself is verified at runtime, against a real browser and a real object store.
 */
public class DataEventsTests {

    @Test
    public void anActiveFormatIsServedAsAnAttachmentSoItCannotRunInThisOrigin() {
        // The point: a file a user uploaded must not execute with the authority of the session that
        // fetches it. Rendering it as a document in this origin is the only way it could, so those types -
        // and only those - are answered as a download.
        assertEquals("attachment; filename=\"payload.html\"",
                DataEvents.contentDisposition("text/html", "payload.html"));
        assertEquals("attachment; filename=\"logo.svg\"",
                DataEvents.contentDisposition("image/svg+xml", "logo.svg"));
        assertEquals("attachment; filename=\"feed.xml\"",
                DataEvents.contentDisposition("application/xml", "feed.xml"));
        // A recorded MIME type carries parameters, and case is not significant in one.
        assertEquals("attachment; filename=\"payload.html\"",
                DataEvents.contentDisposition("TEXT/HTML;charset=UTF-8", "payload.html"));
    }

    @Test
    public void everythingElseIsStillServedInlineSoTheScreensKeepDisplayingIt() {
        // The constraint on the fix: this route is what an <img> in a screen points at, so forcing every
        // payload to download would break the pages that embed content.
        assertEquals("inline; filename=\"photo.png\"",
                DataEvents.contentDisposition("image/png", "photo.png"));
        assertEquals("inline; filename=\"terms.pdf\"",
                DataEvents.contentDisposition("application/pdf", "terms.pdf"));
        assertEquals("inline; filename=\"notes.txt\"",
                DataEvents.contentDisposition("text/plain;charset=UTF-8", "notes.txt"));
        assertEquals("inline", DataEvents.contentDisposition(null, null),
                "with neither a type nor a name the header still has to be well formed");
    }

    @Test
    public void aRecordedNameCannotInjectFurtherHeaderParameters() {
        // dataResourceName is user input. A quotation mark would end the quoted string early and let the
        // rest of the name be read as more parameters of the header.
        assertEquals("inline; filename=\"invoice.pdf\"",
                DataEvents.contentDisposition("application/pdf", "invoice\".pdf"));
        assertEquals("inline; filename=\"note.txt; filename=passwd\"",
                DataEvents.contentDisposition("text/plain", "note.txt; filename=passwd"),
                "a semicolon inside the quoted string is data, and stays; only the quote is removed");
        assertEquals("inline; filename=\"clean.txt\"",
                DataEvents.contentDisposition("text/plain", "cl\rea\nn.txt"),
                "control characters have no place in a header value");
        assertNull(DataEvents.safeDownloadName("\"\"\""),
                "a name left with nothing usable answers no name at all, not an empty one");
        assertNull(DataEvents.safeDownloadName(null), "an absent name stays absent");
    }
}
