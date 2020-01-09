/* Copyright 2017-2020 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.importer.handler.tagger.impl;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.xml.XML;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.handler.ImporterHandlerException;

public class TruncateTaggerTest {

    @Test
    public void testWriteRead() throws IOException {
        TruncateTagger t = new TruncateTagger();
        t.setAppendHash(true);
        t.setFromField("fromField");
        t.setMaxLength(100);
        t.setOnSet(PropertySetter.REPLACE);
        t.setSuffix("suffix");
        t.setToField("toField");
        t.addRestriction("field", "regex", true);

        XML.assertWriteRead(t, "handler");
    }

    @Test
    public void testWithSuffixAndHash() throws ImporterHandlerException {
        ImporterMetadata metadata = new ImporterMetadata();
        metadata.add("from",
                "Please truncate me before you start thinking I am too long.",
                "Another long string to test similar with suffix and no hash",
                "Another long string to test similar without suffix, a hash",
                "Another long string to test similar without suffix, no hash",
                "A small one");

        TruncateTagger t = new TruncateTagger();

        t.setFromField("from");
        t.setToField("to");
        t.setMaxLength(50);
        t.setOnSet(PropertySetter.REPLACE);

        // hash + suffix
        t.setAppendHash(true);
        t.setSuffix("!");
        t.tagDocument("N/A", null, metadata, false);
        Assertions.assertEquals(
                "Please truncate me before you start thi!0996700004",
                metadata.getStrings("to").get(0));
        Assertions.assertNotEquals("Must have different hashes",
                metadata.getStrings("to").get(1),
                metadata.getStrings("to").get(2));

        // no hash + suffix
        t.setAppendHash(false);
        t.setSuffix("...");
        t.tagDocument("N/A", null, metadata, false);
        Assertions.assertEquals(
                "Another long string to test similar with suffix...",
                metadata.getStrings("to").get(1));

        // no hash + suffix
        t.setAppendHash(true);
        t.setSuffix(null);
        t.tagDocument("N/A", null, metadata, false);
        Assertions.assertEquals(
                "Another long string to test similar with0939281732",
                metadata.getStrings("to").get(2));

        // no hash + no suffix
        t.setAppendHash(false);
        t.setSuffix(null);
        t.tagDocument("N/A", null, metadata, false);
        Assertions.assertEquals(
                "Another long string to test similar without suffix",
                metadata.getStrings("to").get(3));

        // too small for truncate
        t.setAppendHash(false);
        t.setSuffix(null);
        t.tagDocument("N/A", null, metadata, false);
        Assertions.assertEquals("A small one", metadata.getStrings("to").get(4));
    }
}
