/*
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Booz Allen Hamilton licenses this file to
 * You under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.language;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.record.path.FieldValue;
import org.apache.nifi.record.path.RecordPath;
import org.apache.nifi.record.path.RecordPathResult;
import org.apache.nifi.record.path.util.RecordPathCache;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.record.Record;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

@CapabilityDescription("Adds a record-based capability to identify the language of a record using a RecordPath operation.")
@Tags({ "language", "identify", "translate" })
@WritesAttributes({
	@WritesAttribute(attribute = IdentifyLanguageRecord.ATTR_EXEC_TIME, description = "Amount of time it took to execute analysis" +
		" of a record set."),
	@WritesAttribute(attribute = IdentifyLanguageRecord.ATTR_RECORD_COUNT, description = "The number of records processed.")
})
public class IdentifyLanguageRecord extends AbstractProcessor {
	public static final PropertyDescriptor READER = new PropertyDescriptor.Builder()
		.name("rest-reader-service")
		.displayName("Record Reader")
		.description("The reader service to use for pulling record data.")
		.identifiesControllerService(RecordReaderFactory.class)
		.required(true)
		.build();
	public static final PropertyDescriptor WRITER = new PropertyDescriptor.Builder()
		.name("rest-writer-service")
		.displayName("Record Writer")
		.description("The writer service to use for writing record data.")
		.identifiesControllerService(RecordSetWriterFactory.class)
		.required(true)
		.build();

	public static final PropertyDescriptor INPUT_RECORD_PATH = new PropertyDescriptor.Builder()
		.name("input-record-path")
		.displayName("Input RecordPath")
		.description("The input record path to use for analyzing the language.")
		.required(true)
		.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
		.addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
		.build();

	public static final PropertyDescriptor OUTPUT_RECORD_PATH = new PropertyDescriptor.Builder()
		.name("output-record-path")
		.displayName("Output RecordPath")
		.description("The output record path to use for storing the result.")
		.required(true)
		.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
		.addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
		.build();

	public static final PropertyDescriptor UNKNOWN_LANGUAGE = new PropertyDescriptor.Builder()
		.name("unknown-language")
		.displayName("Unknown Language Substitution")
		.description("Sometimes the language cannot be discerned from the provided text. The two letter code provided " +
			"here will be used as the default value/assumption when the language cannot be identified.")
		.required(true)
		.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
		.addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
		.build();

	public static final List<PropertyDescriptor> DESCRIPTORS = Collections.unmodifiableList(Arrays.asList(
		READER, WRITER, INPUT_RECORD_PATH, OUTPUT_RECORD_PATH, UNKNOWN_LANGUAGE
	));

	public static final Relationship REL_ORIGINAL = new Relationship.Builder()
		.name("original")
		.description("On success, the original flowfile goes to this relationship.")
		.build();
	public static final Relationship REL_SUCCESS = new Relationship.Builder()
		.name("success")
		.description("On success, all data sent to the enrichment service goes here.")
		.build();
	public static final Relationship REL_FAILURE = new Relationship.Builder()
		.name("failure")
		.description("When the process fails because of an error, the original data is sent here.")
		.build();

	public static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		REL_ORIGINAL, REL_SUCCESS, REL_FAILURE
	)));
	public static final String ATTR_EXEC_TIME = "enrichment.time.execution";
	public static final String ATTR_RECORD_COUNT = "record.count";

	@Override
	public Set<Relationship> getRelationships() {
		return RELATIONSHIPS;
	}

	@Override
	protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return DESCRIPTORS;
	}

	private RecordSetWriterFactory writerFactory;
	private RecordReaderFactory readerFactory;
	private RecordPathCache cache;
	private LanguageDetector detector;

	@OnScheduled
	public void onScheduled(ProcessContext context) {
		writerFactory = context.getProperty(WRITER).asControllerService(RecordSetWriterFactory.class);
		readerFactory = context.getProperty(READER).asControllerService(RecordReaderFactory.class);
		cache = new RecordPathCache(50);
		detector = getDetector();
	}

	@Override
	public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
		FlowFile input = session.get();
		if (input == null) {
			return;
		}

		String inputPath = context.getProperty(INPUT_RECORD_PATH).evaluateAttributeExpressions(input).getValue();
		String outputPath = context.getProperty(OUTPUT_RECORD_PATH).evaluateAttributeExpressions(input).getValue();
		String unknownLanguage = context.getProperty(UNKNOWN_LANGUAGE).evaluateAttributeExpressions(input).getValue();
		RecordPath inputRP = cache.getCompiled(inputPath);
		RecordPath outputRP = cache.getCompiled(outputPath);

		FlowFile output = session.create(input);
		boolean error = false;
		long msDelta = 0;
		long records = 0;

		try (InputStream is = session.read(input);
			 OutputStream os = session.write(output);
			 RecordReader reader = readerFactory.createRecordReader(input, is, getLogger());
			 RecordSetWriter writer = writerFactory.createWriter(getLogger(), reader.getSchema(), os, input)) {

			Record record;
			long start = System.currentTimeMillis();
			writer.beginRecordSet();
			while ((record = reader.nextRecord()) != null) {
				enrich(record, detector, unknownLanguage, inputRP, outputRP);
				writer.write(record);
				if (++records % 100 == 0 && getLogger().isDebugEnabled()) {
					getLogger().debug(String.format("Processed %d records so far.", records));
				}
			}
			msDelta = System.currentTimeMillis() - start;
			writer.finishRecordSet();

			if (getLogger().isDebugEnabled()) {
				getLogger().debug(String.format("Processed %d records total.", records));
			}
		} catch (Exception ex) {
			getLogger().error("", ex);
			error = true;
		} finally {
			if (error) {
				session.remove(output);
				session.transfer(input, REL_FAILURE);
			} else {
				Map<String, String> attrs = new HashMap<>();
				attrs.put(ATTR_EXEC_TIME, String.valueOf(msDelta / 1000));
				attrs.put(ATTR_RECORD_COUNT, String.valueOf(records));

				output = session.putAllAttributes(output, attrs);

				session.transfer(output, REL_SUCCESS);
				session.transfer(input, REL_ORIGINAL);
			}
		}
	}

	@OnUnscheduled
	public void stop() {
		if (detector != null) {
			detector.unloadLanguageModels();
		}
	}

	protected LanguageDetector getDetector() {
		return LanguageDetectorBuilder
			.fromAllLanguages()
			.build();
	}

	private void enrich(Record record, LanguageDetector detector, String unknownLanguage, RecordPath inputRP, RecordPath outputRP) {
		RecordPathResult inputResult = inputRP.evaluate(record);
		RecordPathResult outputResult = outputRP.evaluate(record);
		Optional<FieldValue> inputOpt = inputResult.getSelectedFields().findFirst();
		Optional<FieldValue> outputOpt = outputResult.getSelectedFields().findFirst();

		if (inputOpt.isPresent() && outputOpt.isPresent()) {
			FieldValue input = inputOpt.get();
			FieldValue output = outputOpt.get();

			Object rawInput = input.getValue();
			if (rawInput != null) {
				Language detected = detector.detectLanguageOf(rawInput.toString());
				if (detected == null || detected == Language.UNKNOWN) {
					output.updateValue(unknownLanguage);
				} else {
					output.updateValue(detected.getIsoCode639_1().toString().toLowerCase());
				}
			}
		}
	}
}
