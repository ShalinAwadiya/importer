/* Copyright 2016-2018 Norconex Inc.
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.xml.XML;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.handler.ImporterHandlerException;
import com.norconex.importer.handler.tagger.AbstractDocumentTagger;

/**
 * <p>
 * Merge multiple metadata fields into a single one.
 * </p>
 * <p>
 * Use <code>fromFields</code> to list all fields to merge, separated by commas.
 * Use <code>fromFieldsRegex</code> to match fields to merge using a regular
 * expression.
 * Both <code>fromFields</code> and <code>fromFieldsRegex</code> can be used
 * together. Matching fields from both will be combined, in the order
 * provided/matched, starting with <code>fromFields</code> entries.
 * </p>
 * <p>
 * Unless
 * <code>singleValue</code> is set to <code>true</code>, each value will be
 * added to the target field, making it a multi-value field.  If
 * <code>singleValue</code> is set to <code>true</code>,
 * all values will be combined into one string, optionally
 * separated by the <code>singleValueSeparator</code>.  Single values will
 * be constructed without any separator if none are specified.
 * </p>
 * <p>
 * You can optionally decide do delete source fields after they were merged
 * by setting <code>deleteFromFields</code> to <code>true</code>.
 * </p>
 * <p>
 * The target field can be one of the "from" fields. In such case its
 * content will be replaced with the result of the merge (it will not be
 * deleted even if <code>deleteFromFields</code> is <code>true</code>).
 * </p>
 * <p>
 * If only a single source field is specified or found, it will be copied
 * to the target field and its multi-values will still be merged to a single one
 * if configured to do so.  In such cases, this class can become an alternative
 * to using {@link ForceSingleValueTagger} with a "mergeWith" action.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;handler class="com.norconex.importer.handler.tagger.impl.MergeTagger"&gt;
 *
 *      &lt;restrictTo caseSensitive="[false|true]"
 *              field="(name of header/metadata field name to match)"&gt;
 *          (regular expression of value to match)
 *      &lt;/restrictTo&gt;
 *      &lt;!-- multiple "restrictTo" tags allowed (only one needs to match) --&gt;
 *
 *      &lt;merge toField="(name of target field for merged values)"
 *             deleteFromFields="[false|true]"
 *             singleValue="[false|true]"
 *             singleValueSeparator="(text joining multiple-values)" &gt;
 *        &lt;fromFields&gt;(coma-separated list of fields to merge)&lt;/fromFields&gt;
 *        &lt;fromFieldsRegex&gt;(regular expression matching fields to merge)&lt;/fromFieldsRegex&gt;
 *      &lt;/merge&gt;
 *      &lt;!-- multiple merge tags allowed --&gt;
 *
 *  &lt;/handler&gt;
 * </pre>
 * <h4>Usage example:</h4>
 * <p>
 * The following merges several title fields into one, joining multiple
 * occurrences with a coma, and deleting original fields.
 * </p>
 * <pre>
 *  &lt;handler class="com.norconex.importer.handler.tagger.impl.MergeTagger"&gt;
 *      &lt;merge toField="title" deleteFromFields="true"
 *             singleValue="true" singleValueSeparator="," &gt;
 *        &lt;fromFields&gt;title,dc.title,dc:title,doctitle&lt;/fromFields&gt;
 *      &lt;/merge&gt;
 *  &lt;/handler&gt;
 * </pre>
 *
 * @author Pascal Essiembre
 * @since 2.7.0
 */
public class MergeTagger extends AbstractDocumentTagger {

    private final List<Merge> merges = new ArrayList<>();

    @Override
    public void tagApplicableDocument(
            String reference, InputStream document,
            ImporterMetadata metadata, boolean parsed)
            throws ImporterHandlerException {

        for (Merge merge : merges) {
            Map<String, List<String>> toMerge = new LinkedHashMap<>();
            // From CSV
            if (!merge.getFromFields().isEmpty()) {
                for (String fromField : merge.getFromFields()) {
                    if (metadata.containsKey(fromField)) {
                        toMerge.put(fromField, metadata.getStrings(fromField));
                    }
                }
            }
            // From Regex
            if (StringUtils.isNotBlank(merge.getFromFieldsRegex())) {
                Pattern p = Pattern.compile(merge.getFromFieldsRegex());
                for (Entry<String, List<String>> entry : metadata.entrySet()) {
                    if (p.matcher(entry.getKey()).matches()) {
                        toMerge.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            // Merge values in one list
            List<String> mergedValues = new ArrayList<>();
            for (List<String> propValues : toMerge.values()) {
                mergedValues.addAll(propValues);
            }

            // Delete if necessary
            if (merge.isDeleteFromFields()) {
                for (String field : toMerge.keySet()) {
                    metadata.remove(field);
                }
            }

            // Store in target field
            if (merge.isSingleValue()) {
                metadata.set(merge.getToField(), StringUtils.join(
                        mergedValues, merge.getSingleValueSeparator()));
            } else {
                metadata.put(merge.getToField(), mergedValues);
            }
        }
    }

    public List<Merge> getMerges() {
        return Collections.unmodifiableList(merges);
    }
    public void addMerge(Merge merge) {
        merges.add(merge);
    }

    public static class Merge {
        private final List<String> fromFields = new ArrayList<>();
        private String fromFieldsRegex;
        private boolean deleteFromFields;
        private String toField;
        private boolean singleValue;
        private String singleValueSeparator;
        public Merge() {
            super();
        }
        public List<String> getFromFields() {
            return Collections.unmodifiableList(fromFields);
        }
        public void setFromFields(List<String> fromFields) {
            CollectionUtil.setAll(this.fromFields, fromFields);
        }
        public String getFromFieldsRegex() {
            return fromFieldsRegex;
        }
        public void setFromFieldsRegex(String fromFieldsRegex) {
            this.fromFieldsRegex = fromFieldsRegex;
        }
        public boolean isDeleteFromFields() {
            return deleteFromFields;
        }
        public void setDeleteFromFields(boolean deleteFromFields) {
            this.deleteFromFields = deleteFromFields;
        }
        public String getToField() {
            return toField;
        }
        public void setToField(String toField) {
            this.toField = toField;
        }
        public boolean isSingleValue() {
            return singleValue;
        }
        public void setSingleValue(boolean singleValue) {
            this.singleValue = singleValue;
        }
        public String getSingleValueSeparator() {
            return singleValueSeparator;
        }
        public void setSingleValueSeparator(String singleValueSeparator) {
            this.singleValueSeparator = singleValueSeparator;
        }

        @Override
        public boolean equals(final Object other) {
            return EqualsBuilder.reflectionEquals(this, other);
        }
        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
        @Override
        public String toString() {
            return new ReflectionToStringBuilder(
                    this, ToStringStyle.SHORT_PREFIX_STYLE).toString();
        }
    }

    @Override
    protected void loadHandlerFromXML(XML xml) {
        List<XML> nodes = xml.getXMLList("merge");
        for (XML node : nodes) {
            Merge m = new Merge();
            m.setToField(node.getString("@toField", m.getToField()));
            m.setDeleteFromFields(node.getBoolean(
                    "@deleteFromFields", m.isDeleteFromFields()));
            m.setSingleValue(node.getBoolean(
                    "@singleValue", m.isSingleValue()));
            m.setSingleValueSeparator(node.getString(
                    "@singleValueSeparator", m.getSingleValueSeparator()));
            m.setFromFields(node.getDelimitedStringList(
                    "fromFields", m.getFromFields()));
            m.setFromFieldsRegex(node.getString(
                    "fromFieldsRegex", m.getFromFieldsRegex()));
            addMerge(m);
        }
    }

    @Override
    protected void saveHandlerToXML(XML xml) {
        for (Merge m : merges) {
            XML mergeXML = xml.addElement("merge")
                    .setAttribute("toField", m.getToField())
                    .setAttribute("deleteFromFields", m.isDeleteFromFields())
                    .setAttribute("singleValue", m.isSingleValue())
                    .setAttribute("singleValueSeparator",
                            m.getSingleValueSeparator());
            mergeXML.addDelimitedElementList("fromFields", m.getFromFields());
            mergeXML.addElement("fromFieldsRegex", m.getFromFieldsRegex());
        }
    }

    @Override
    public boolean equals(final Object other) {
        return EqualsBuilder.reflectionEquals(this, other);
    }
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
    @Override
    public String toString() {
        return new ReflectionToStringBuilder(
                this, ToStringStyle.SHORT_PREFIX_STYLE).toString();
    }
}
