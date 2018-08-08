/* Copyright 2015-2018 Norconex Inc.
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.commons.lang.xml.XML;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.ImporterHandlerException;
import com.norconex.importer.handler.tagger.AbstractDocumentTagger;
import com.norconex.importer.util.DOMUtil;

/**
 * <p>Extract the value of one or more elements or attributes into
 * a target field, from and HTML, XHTML, or XML document. If a target field
 * already exists, extracted values will be added to existing values,
 * unless "overwrite" is set to <code>true</code>.</p>
 * <p>
 * This class constructs a DOM tree from the document content. That DOM tree
 * is loaded entirely into memory. Use this tagger with caution if you know
 * you'll need to parse huge files. It may be preferable to use
 * {@link TextPatternTagger} if this is a concern. Also, to help performance
 * and avoid re-creating DOM tree before every DOM extraction you want to
 * perform, try to combine multiple extractions in a single instance
 * of this Tagger.
 * </p>
 * <p>
 * The <a href="http://jsoup.org/">jsoup</a> parser library is used to load a
 * document content into a DOM tree. Elements are referenced using a
 * <a href="http://jsoup.org/cookbook/extracting-data/selector-syntax">
 * CSS or JQuery-like syntax</a>.
 * </p>
 * <p>Should be used as a pre-parse handler.</p>
 *
 * <h3>Content-types</h3>
 * <p>
 * By default, this filter is restricted to (applies only to) documents matching
 * the restrictions returned by
 * {@link CommonRestrictions#domContentTypes()}.
 * You can specify your own content types if you know they represent a file
 * with HTML or XML-like markup tags.
 * </p>
 *
 * <p><b>Since 2.5.0</b>, when used as a pre-parse handler,
 * this class attempts to detect the content character
 * encoding unless the character encoding
 * was specified using {@link #setSourceCharset(String)}. Since document
 * parsing converts content to UTF-8, UTF-8 is always assumed when
 * used as a post-parse handler.
 * </p>
 *
 * <p><b>Since 2.5.0</b>, it is possible to control what gets extracted
 * exactly thanks to the "extract" argument of the new method
 * {@link DOMExtractDetails#setExtract(String)}. Version 2.6.0
 * introduced several more extract options. Possible values are:</p>
 * <ul>
 *   <li><b>text</b>: Default option when extract is blank. The text of
 *       the element, including combined children.</li>
 *   <li><b>html</b>: Extracts an element inner
 *       HTML (including children).</li>
 *   <li><b>outerHtml</b>: Extracts an element outer
 *       HTML (like "html", but includes the "current" tag).</li>
 *   <li><b>ownText</b>: Extracts the text owned by this element only;
 *       does not get the combined text of all children.</li>
 *   <li><b>data</b>: Extracts the combined data of a data-element (e.g.
 *       &lt;script&gt;).</li>
 *   <li><b>id</b>: Extracts the ID attribute of the element (if any).</li>
 *   <li><b>tagName</b>: Extract the name of the tag of the element.</li>
 *   <li><b>val</b>: Extracts the value of a form element
 *       (input, textarea, etc).</li>
 *   <li><b>className</b>: Extracts the literal value of the element's
 *       "class" attribute, which may include multiple class names,
 *       space separated.</li>
 *   <li><b>cssSelector</b>: Extracts a CSS selector that will uniquely
 *       select (identify) this element.</li>
 *   <li><b>attr(attributeKey)</b>: Extracts the value of the element
 *       attribute matching your replacement for "attributeKey"
 *       (e.g. "attr(title)" will extract the "title" attribute).</li>
 * </ul>
 *
 * <p><b>Since 2.6.0</b>, it is possible to specify a <code>fromField</code>
 * as the source of the HTML to parse instead of using the document content.
 * If multiple values are present for that source field, DOM extraction will be
 * applied to each value.
 * </p>
 *
 * <p><b>Since 2.6.0</b>, it is possible to specify a <code>defaultValue</code>
 * on each DOM extraction details. When no match occurred for a given selector,
 * the default value will be stored in the <code>toField</code> (as opposed
 * to not storing anything).  When matching blanks (see below) you will get
 * an empty string as opposed to the default value.
 * As of 2.6.1, empty strings and spaces are supported as default values
 * (the default value is now taken litterally).
 * </p>
 *
 * <p><b>Since 2.6.1</b>, you can set <code>matchBlanks</code> to
 * <code>true</code> to match elements that are present
 * but have blank values. Blank values are empty values or values containing
 * white spaces only. Because white spaces are normalized by the DOM parser,
 * such matches will always return an empty string (spaces will be trimmed).
 * By default elements with blank values are not matched and are ignored.
 * </p>
 *
 * <p><b>Since 2.8.0</b>, you can specify which parser to use when reading
 * documents. The default is "html" and will normalize the content
 * as HTML. This is generally a desired behavior, but this can sometimes
 * have your selector fail. If you encounter this
 * problem, try switching to "xml" parser, which does not attempt normalization
 * on the content. The drawback with "xml" is you may not get all HTML-specific
 * selector options to work.  If you know you are dealing with XML to begin
 * with, specifying "xml" should be a good option.
 * </p>
 *
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;handler class="com.norconex.importer.handler.tagger.impl.DOMTagger"
 *          fromField="(optional source field)"
 *          parser="[html|xml]"
 *          sourceCharset="(character encoding)"&gt;
 *
 *      &lt;restrictTo
 *              caseSensitive="[false|true]"
 *              field="(name of metadata field name to match)"&gt;
 *          (regular expression of value to match)
 *      &lt;/restrictTo&gt;
 *      &lt;!-- multiple "restrictTo" tags allowed (only one needs to match) --&gt;
 *
 *      &lt;dom selector="(selector syntax)"
 *              toField="(target field)"
 *              overwrite="[false|true]"
 *              extract="[text|html|outerHtml|ownText|data|tagName|val|className|cssSelector|attr(attributeKey)]"
 *              matchBlanks="[false|true]"
 *              defaultValue="(optional value to use when no match)" /&gt;
 *      &lt;!-- multiple "dom" tags allowed --&gt;
 *  &lt;/handler&gt;
 * </pre>
 *
 * <h4>Usage example:</h4>
 * <p>
 * Given this HTML snippet...
 * </p>
 * <pre>
 * &lt;div class="firstName"&gt;Joe&lt;/div&gt;
 * &lt;div class="lastName"&gt;Dalton&lt;/div&gt;
 * </pre>
 * <p>
 * ... the following will store "Joe" in a "firstName" field and "Dalton"
 * in a "lastName" field.
 * </p>
 * <pre>
 *  &lt;handler class="com.norconex.importer.handler.tagger.impl.DOMTagger"&gt;
 *      &lt;dom selector="div.firstName" toField="firstName" /&gt;
 *      &lt;dom selector="div.lastName"  toField="lastName" /&gt;
 *  &lt;/handler&gt;
 * </pre>
 * @author Pascal Essiembre
 * @since 2.4.0
 */
public class DOMTagger extends AbstractDocumentTagger {

    private static final Logger LOG = LoggerFactory.getLogger(DOMTagger.class);

    private final List<DOMExtractDetails> extractions = new ArrayList<>();
    private String sourceCharset = null;
    private String fromField = null;
    private String parser = DOMUtil.PARSER_HTML;

    /**
     * Constructor.
     */
    public DOMTagger() {
        super();
        addRestrictions(CommonRestrictions.domContentTypes());
    }

    /**
     * Gets the assumed source character encoding.
     * @return character encoding of the source to be transformed
     * @since 2.5.0
     */
    public String getSourceCharset() {
        return sourceCharset;
    }
    /**
     * Sets the assumed source character encoding.
     * @param sourceCharset character encoding of the source to be transformed
     * @since 2.5.0
     */
    public void setSourceCharset(String sourceCharset) {
        this.sourceCharset = sourceCharset;
    }

    /**
     * Gets optional source field holding the HTML content to apply DOM
     * extraction to.
     * @return from field
     * @since 2.6.0
     */
    public String getFromField() {
        return fromField;
    }
    /**
     * Sets optional source field holding the HTML content to apply DOM
     * extraction to.
     * @param fromField from field
     * @since 2.6.0
     */
    public void setFromField(String fromField) {
        this.fromField = fromField;
    }

    /**
     * Gets the parser to use when creating the DOM-tree.
     * @return <code>html</code> (default) or <code>xml</code>.
     * @since 2.8.0
     */
    public String getParser() {
        return parser;
    }
    /**
     * Sets the parser to use when creating the DOM-tree.
     * @param parser <code>html</code> or <code>xml</code>.
     * @since 2.8.0
     */
    public void setParser(String parser) {
        this.parser = parser;
    }

    @Override
    protected void tagApplicableDocument(String reference,
            InputStream document, ImporterMetadata metadata, boolean parsed)
            throws ImporterHandlerException {

        try {
            List<Document> jsoupDocs = new ArrayList<>();

            // Use "fromField" as content
            if (StringUtils.isNotBlank(getFromField())) {
                List<String> htmls = metadata.getStrings(getFromField());
                if (htmls.isEmpty()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Field \"" + getFromField() + "\" has no "
                                + "value. No DOM extraction performed.");
                    }
                    return;
                }
                for (String html : htmls) {
                    jsoupDocs.add(Jsoup.parse(html,
                            reference, DOMUtil.toJSoupParser(getParser())));
                }
            // Use doc content
            } else {
                String inputCharset = detectCharsetIfBlank(
                        sourceCharset, reference, document, metadata, parsed);
                jsoupDocs.add(Jsoup.parse(document, inputCharset,
                        reference, DOMUtil.toJSoupParser(getParser())));
            }
            domExtractDocList(jsoupDocs, metadata);
        } catch (IOException e) {
            throw new ImporterHandlerException(
                    "Cannot extract DOM element(s) from DOM-tree.", e);
        }
    }

    private void domExtractDocList(
            List<Document> jsoupDocs, ImporterMetadata metadata) {
        for (DOMExtractDetails details : extractions) {
            List<String> extractedValues = new ArrayList<>();
            for (Document doc : jsoupDocs) {
                domExtractDoc(extractedValues, doc, details);
            }
            if (!extractedValues.isEmpty()) {
                String[] vals = extractedValues.toArray(
                        ArrayUtils.EMPTY_STRING_ARRAY);
                if (details.overwrite) {
                    metadata.setString(details.toField, vals);
                } else {
                    metadata.addString(details.toField, vals);
                }
            }
        }
    }

    private void domExtractDoc(List<String> extractedValues,
            Document doc, DOMExtractDetails details) {
        Elements elms = doc.select(details.selector);
        boolean hasDefault = details.getDefaultValue() != null;

        // no elements matching
        if (elms.isEmpty()) {
            if (hasDefault) {
                extractedValues.add(details.getDefaultValue());
            }
            return;
        }

        // one or more elements matching
        for (Element elm : elms) {
            String value = DOMUtil.getElementValue(elm, details.extract);
            // JSoup normalizes white spaces and should always trim them,
            // but we force it here to ensure 100% consistency.
            value = StringUtils.trim(value);
            boolean matches = !(value == null
                    || !details.matchBlanks && StringUtils.isBlank(value));
            if (matches) {
                extractedValues.add(value);
            } else if (hasDefault) {
                extractedValues.add(details.getDefaultValue());
            }
        }
    }

    /**
     * Adds DOM extraction details.
     * @param extractDetails DOM extraction details
     * @since 2.6.0
     */
    public void addDOMExtractDetails(DOMExtractDetails extractDetails) {
        if (extractDetails != null) {
            extractions.add(extractDetails);
        }
    }

    /**
     * Gets a list of DOM extraction details.
     * @return list of DOM extraction details.
     * @since 2.6.0
     */
    public List<DOMExtractDetails> getDOMExtractDetailsList() {
        return Collections.unmodifiableList(extractions);
    }

    /**
     * Removes the DOM extraction details matching the given selector
     * @param selector DOM selector
     * @since 2.6.0
     */
    public void removeDOMExtractDetails(String selector) {
        List<DOMExtractDetails> toRemove = new ArrayList<>();
        for (DOMExtractDetails details : extractions) {
            if (Objects.equals(details.getSelector(), selector)) {
                toRemove.add(details);
            }
        }
        synchronized (extractions) {
            extractions.removeAll(toRemove);
        }
    }

    @Override
    protected void loadHandlerFromXML(XML xml) {
        setSourceCharset(xml.getString("@sourceCharset", sourceCharset));
        setFromField(xml.getString("@fromField", fromField));
        setParser(xml.getString("@parser", parser));
        List<XML> nodes = xml.getXMLList("dom");
        if (!nodes.isEmpty()) {
            extractions.clear();
        }
        for (XML node : nodes) {
            DOMExtractDetails details = new DOMExtractDetails(
                    node.getString("@selector", null),
                    node.getString("@toField", null),
                    node.getBoolean("@overwrite", false),
                    node.getString("@extract", null));
            details.setMatchBlanks(node.getBoolean("@matchBlanks", false));
            details.setDefaultValue(node.getString("@defaultValue", null));
            addDOMExtractDetails(details);
        }
    }

    @Override
    protected void saveHandlerToXML(XML xml) {
        xml.setAttribute("sourceCharset", sourceCharset);
        xml.setAttribute("fromField", fromField);
        xml.setAttribute("parser", parser);
        for (DOMExtractDetails details : extractions) {
            xml.addElement("dom")
                    .setAttribute("selector", details.getSelector())
                    .setAttribute("toField", details.getToField())
                    .setAttribute("overwrite", details.isOverwrite())
                    .setAttribute("extract", details.getExtract())
                    .setAttribute("matchBlanks", details.isMatchBlanks())
                    .setAttribute("defaultValue", details.getDefaultValue());
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

    /**
     * DOM Extraction Details
     * @author Pascal Essiembre
     * @since 2.6.0
     */
    public static class DOMExtractDetails {
        private String selector;
        private String toField;
        private boolean overwrite;
        private String extract;
        private boolean matchBlanks;
        private String defaultValue;

        public DOMExtractDetails() {
            super();
        }
        public DOMExtractDetails(
                String selector, String to, boolean overwrite) {
            this(selector, to, overwrite, null);
        }
        public DOMExtractDetails(
                String selector, String to, boolean overwrite, String extract) {
            this.selector = selector;
            this.toField = to;
            this.overwrite = overwrite;
            this.extract = extract;
        }

        public String getSelector() {
            return selector;
        }
        public void setSelector(String selector) {
            this.selector = selector;
        }
        public String getToField() {
            return toField;
        }
        public void setToField(String toField) {
            this.toField = toField;
        }
        public boolean isOverwrite() {
            return overwrite;
        }
        public void setOverwrite(boolean overwrite) {
            this.overwrite = overwrite;
        }
        public String getExtract() {
            return extract;
        }
        public void setExtract(String extract) {
            this.extract = extract;
        }
        /**
         * Gets whether lements with blank values should be considered a
         * match and have an empty string returned as opposed to nothing at all.
         * Default is <code>false</code>;
         * @return <code>true</code> if elements with blank values are supported
         * @since 2.6.1
         */
        public boolean isMatchBlanks() {
            return matchBlanks;
        }
        /**
         * Sets whether elements with blank values should be considered a
         * match and have an empty string returned as opposed to nothing at all.
         * @param matchBlanks <code>true</code> to support elements with
         *                    blank values
         * @since 2.6.1
         */
        public void setMatchBlanks(boolean matchBlanks) {
            this.matchBlanks = matchBlanks;
        }
        public String getDefaultValue() {
            return defaultValue;
        }
        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
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
}
