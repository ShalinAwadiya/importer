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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.xml.XML;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.handler.ImporterHandlerException;
import com.norconex.importer.handler.tagger.AbstractStringTagger;

/**
 * <p>Attempts to generate a title from the document content (default) or
 * a specified metadata field. It does not consider a document format
 * to give value to more terms than other. For instance, it would not
 * consider text found in &lt;H1&gt; tags more importantly than other
 * text in HTML documents.</p>
 *
 * <p>If {@link #isDetectHeading()} returns <code>true</code>, this handler
 * will check if the content starts with a stand-alone, single-sentence line
 * (which could be the actual title).
 * That is, a line of text with only one sentence in it, followed by one or
 * more new line characters. To help
 * eliminate cases where such sentence are inappropriate, you can specify a
 * minimum and maximum number of characters that first line should have
 * with {@link #setDetectHeadingMinLength(int)} and
 * {@link #setDetectHeadingMaxLength(int)} (e.g. to ignore "Page 1" text and
 * the like).</p>
 *
 * <p>Unless a target field name is provided, the default field name
 * where the title will be stored is <code>document.generatedTitle</code>.
 * Unless, {@link #setOverwrite(boolean)} is set to <code>true</code>,
 * no title will be generated if one already exists in the target field.</p>
 *
 * <p>If it cannot generate a title, it will fall-back to retrieving the
 * first sentence from the text.</p>
 *
 * <p>The generated title length is limited to 150 characters by default.
 * You can change that limit by using
 * {@link #setTitleMaxLength(int)}. Text larger than the max limit will be
 * truncated and three dots will be added in square brackets [...].
 * To remove the limit,
 * use -1 (or constant {@link #UNLIMITED_TITLE_LENGTH}).</p>
 *
 * <p>This class should be used as a post-parsing handler only
 * (or otherwise on unformatted text).</p>
 *
 * <p><b>Since 2.2.0</b>, the algorithm to detect titles has been much
 * simplified to eliminate extra dependencies that were otherwise not required.
 * It uses a generic statistics-based approach to weight each sentences
 * up to a certain amount, and simply returns the sentence that the most
 * weight given a minimum threshold has been met.  You are strongly encouraged
 * to use a more sophisticated summarization engine if you want more
 * accurate titles generated.
 * </p>
 *
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;handler class="com.norconex.importer.handler.tagger.impl.TitleGeneratorTagger"
 *          fromField="(field of text to use/default uses document content)"
 *          toField="(target field where to store generated title)"
 *          overwrite="[false|true]"
 *          titleMaxLength="(max num of chars for generated title)"
 *          detectHeading="[false|true]"
 *          detectHeadingMinLength="(min length a heading title can have)"
 *          detectHeadingMaxLength="(max length a heading title can have)"
 *          sourceCharset="(character encoding)"
 *          maxReadSize="(max characters to read at once)" &gt;
 *
 *      &lt;restrictTo caseSensitive="[false|true]"
 *              field="(name of header/metadata field name to match)"&gt;
 *          (regular expression of value to match)
 *      &lt;/restrictTo&gt;
 *      &lt;!-- multiple "restrictTo" tags allowed (only one needs to match) --&gt;
 *  &lt;/handler&gt;
 * </pre>
 * <h4>Usage example:</h4>
 * <p>
 * The following will check if the first line looks like a title and if not,
 * it will store the first sentence, up to 200 characters, in a field called
 * title.
 * </p>
 * <pre>
 *  &lt;handler class="com.norconex.importer.handler.tagger.impl.TitleGeneratorTagger"
 *          toField="title" titleMaxLength="200" detectHeading="true" /&gt;
 * </pre>
 *
 * @author Pascal Essiembre
 * @since 2.1.0
 */
public class TitleGeneratorTagger
        extends AbstractStringTagger implements IXMLConfigurable {

    // Minimum length a term should have to be considered valuable
    private static final int MIN_TERM_LENGTH = 4;

    // Minimum number of occurrences a term should have to be considered valuable
    private static final int MIN_OCCURENCES = 3;
    //TODO have a max num terms?

    private final EntryValueComparator entryValueComparator =
            new EntryValueComparator();

    public static final String DEFAULT_TO_FIELD =
            ImporterMetadata.DOC_GENERATED_TITLE;
    public static final int DEFAULT_TITLE_MAX_LENGTH = 150;
    public static final int UNLIMITED_TITLE_LENGTH = -1;
    public static final int DEFAULT_HEADING_MIN_LENGTH = 10;
    public static final int DEFAULT_HEADING_MAX_LENGTH = 150;

    private static final Pattern PATTERN_HEADING = Pattern.compile(
            "^.*?([^\\n\\r]+)[\\n\\r]", Pattern.DOTALL);

    private String fromField;
    private String toField = DEFAULT_TO_FIELD;
    private boolean overwrite;
    private int titleMaxLength = DEFAULT_TITLE_MAX_LENGTH;
    private boolean detectHeading;
    private int detectHeadingMinLength = DEFAULT_HEADING_MIN_LENGTH;
    private int detectHeadingMaxLength = DEFAULT_HEADING_MAX_LENGTH;


    @Override
    protected void tagStringContent(String reference, StringBuilder content,
            ImporterMetadata metadata, boolean parsed, int sectionIndex)
                    throws ImporterHandlerException {

        // The first chunk already did the title generation.
        if (sectionIndex > 0) {
            return;
        }

        // If title already exists and not overwriting, leave now
        if (overwrite && StringUtils.isNotBlank(
                metadata.getString(getTargetField()))) {
            return;
        }

        // Get the text to evaluate
        String text = null;
        if (StringUtils.isNotBlank(fromField)) {
            text = metadata.getString(fromField);
        } else {
            text = content.toString();
        }

        // Make sure text is not null
        if (text == null) {
            text = StringUtils.EMPTY;
        }

        String title = null;

        // Try detecting if there is a text heading
        if (isDetectHeading()) {
            title = getHeadingTitle(text);
        }

        // No heading, then try stats-based summarizing
        if (StringUtils.isBlank(title)) {
            title = summarize(text);
        }

        // If we got one, store it
        if (StringUtils.isNotBlank(title)) {
            if (titleMaxLength >= 0 && title.length() > titleMaxLength) {
                title = StringUtils.substring(title, 0, titleMaxLength);
                title += "[...]";
            }
            metadata.setString(getTargetField(), title);
        }
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

    public String getFromField() {
        return fromField;
    }
    public void setFromField(String fromField) {
        this.fromField = fromField;
    }

    public int getTitleMaxLength() {
        return titleMaxLength;
    }
    public void setTitleMaxLength(int titleMaxLength) {
        this.titleMaxLength = titleMaxLength;
    }

    public boolean isDetectHeading() {
        return detectHeading;
    }
    public void setDetectHeading(boolean detectHeading) {
        this.detectHeading = detectHeading;
    }

    public int getDetectHeadingMinLength() {
        return detectHeadingMinLength;
    }
    public void setDetectHeadingMinLength(int detectHeadingMinLength) {
        this.detectHeadingMinLength = detectHeadingMinLength;
    }

    public int getDetectHeadingMaxLength() {
        return detectHeadingMaxLength;
    }
    public void setDetectHeadingMaxLength(int detectHeadingMaxLength) {
        this.detectHeadingMaxLength = detectHeadingMaxLength;
    }

    private String getTargetField() {
        if (StringUtils.isBlank(toField)) {
            return DEFAULT_TO_FIELD;
        }
        return toField;
    }

    private String getHeadingTitle(String text) {
        String firstLine = null;
        Matcher m = PATTERN_HEADING.matcher(text);
        if (m.find()) {
            firstLine = StringUtils.trim(m.group());
        }
        if (StringUtils.isBlank(firstLine)) {
            return null;
        }

        // if more than one sentence, ignore
        if (StringUtils.split(firstLine, "?!.").length != 1) {
            return null;
        }
        // must match min/max lengths.
        if (firstLine.length() < detectHeadingMinLength
                || firstLine.length() > detectHeadingMaxLength) {
            return null;
        }
        return firstLine;
    }


    //***********************************************************

    private String summarize(String text) {

        Index index = indexText(text);
        if (index.sentences.isEmpty()) {
            return StringUtils.EMPTY;
        }
        long topScore = 0;
        String topSentence = index.sentences.get(0);
        for (String  sentence : index.sentences) {
            long score = 0;
            long densityFactor = 500 - sentence.length();
            for (TermOccurence to : index.terms) {
                Matcher m = Pattern.compile(
                        "\\b\\Q" + to.term + "\\E\\b").matcher(sentence);
                int count = 0;
                while (m.find()) {
                    count++;
                }
                if (count > 0) {
                    score += (count * to.occurence * densityFactor);
                }
            }
            if (score > topScore) {
                topScore = score;
                topSentence = sentence;
            }
        }
        return topSentence;
    }

    private Index indexText(String text) {
        Index index = new Index();
        ConcurrentMap<String, AtomicInteger> terms = new ConcurrentHashMap<>();

        // Allow to pass locale, based on language field?
        BreakIterator breakIterator = BreakIterator.getSentenceInstance();
        breakIterator.setText(text);

        int start = breakIterator.first();
        int end = breakIterator.next();
        while (end != BreakIterator.DONE) {
            String matchText = text.substring(start,end).trim();
            String[] sentences = matchText.split("[\\n\\r]");
            for (String sentence : sentences) {
                String s = StringUtils.trimToNull(sentence);
                if (s != null
                        && Character.isLetterOrDigit(sentence.codePointAt(0))) {
                    index.sentences.add(sentence);
                    breakWords(sentence, terms);
                }
            }
            start = end;
            end = breakIterator.next();
        }

        List<Entry<String, AtomicInteger>> sorted =
                new ArrayList<>(terms.entrySet());
        Collections.sort(sorted, entryValueComparator);
        for (Entry<String, AtomicInteger> entry : sorted) {
            String term = entry.getKey();
            int occurences = entry.getValue().get();
            if (term.length() >= MIN_TERM_LENGTH
                    && occurences >= MIN_OCCURENCES) {
                index.terms.add(new TermOccurence(term, occurences));
            }
        }
        return index;
    }
    private void breakWords(
            String sentence, ConcurrentMap<String, AtomicInteger> terms) {

        BreakIterator wordIterator = BreakIterator.getWordInstance();
        wordIterator.setText(sentence);
        int start = wordIterator.first();
        int end = wordIterator.next();

        while (end != BreakIterator.DONE) {
            String word = sentence.substring(start,end);
            if (Character.isLetterOrDigit(word.codePointAt(0))) {
                terms.putIfAbsent(word, new AtomicInteger(0));
                terms.get(word).incrementAndGet();
            }
            start = end;
            end = wordIterator.next();
        }
    }

    @Override
    protected void loadStringTaggerFromXML(XML xml) {
        setFromField(xml.getString("@fromField", fromField));
        setToField(xml.getString("@toField", toField));
        setOverwrite(xml.getBoolean("@overwrite", overwrite));
        setTitleMaxLength(xml.getInteger("@titleMaxLength", titleMaxLength));
        setDetectHeading(xml.getBoolean("@detectHeading", detectHeading));
        setDetectHeadingMinLength(xml.getInteger(
                "@detectHeadingMinLength", detectHeadingMinLength));
        setDetectHeadingMaxLength(xml.getInteger(
                "@detectHeadingMaxLength", detectHeadingMaxLength));
    }

    @Override
    protected void saveStringTaggerToXML(XML xml) {
        xml.setAttribute("fromField", fromField);
        xml.setAttribute("toField", toField);
        xml.setAttribute("overwrite", overwrite);
        xml.setAttribute("titleMaxLength", titleMaxLength);
        xml.setAttribute("detectHeading", detectHeading);
        xml.setAttribute("detectHeadingMinLength", detectHeadingMinLength);
        xml.setAttribute("detectHeadingMaxLength", detectHeadingMaxLength);
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

    //--- Inner classes --------------------------------------------------------
    class EntryValueComparator
            implements Comparator<Entry<String, AtomicInteger>> {
        @Override
        public int compare(Entry<String, AtomicInteger> o1,
                Entry<String, AtomicInteger> o2) {
            return Integer.compare(o2.getValue().get(), o1.getValue().get());
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
    class Index {
        private final List<String> sentences = new ArrayList<>();
        private final List<TermOccurence> terms = new ArrayList<>();
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
    class TermOccurence implements Comparable<TermOccurence> {
        private final String term;
        private final int occurence;
        public TermOccurence(String term, int occurence) {
            super();
            this.term = term;
            this.occurence = occurence;
        }
        @Override
        public int compareTo(TermOccurence o) {
            return Integer.compare(occurence, o.occurence);
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
