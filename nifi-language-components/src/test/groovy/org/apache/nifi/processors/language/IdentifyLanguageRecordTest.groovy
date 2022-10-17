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
package org.apache.nifi.processors.language

import org.apache.nifi.serialization.record.MockRecordParser
import org.apache.nifi.serialization.record.MockRecordWriter
import org.apache.nifi.serialization.record.RecordFieldType
import org.apache.nifi.util.TestRunner
import org.apache.nifi.util.TestRunners
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IdentifyLanguageRecordTest {
    TestRunner runner

    static final SPANISH_SAMPLE = "estoy jugando con mi perro"
    static final ENGLISH_SAMPLE = "I am playing with my dog"
    static final UNKNOWN_SAMPLE = "76885673642525"

    @BeforeEach
    void setup() {
        def reader = new MockRecordParser()
        reader.addSchemaField("phrase", RecordFieldType.STRING)
        reader.addSchemaField("language", RecordFieldType.STRING, true)
        reader.addRecord(SPANISH_SAMPLE, "")
        reader.addRecord(ENGLISH_SAMPLE, "")
        reader.addRecord(UNKNOWN_SAMPLE, "")
        def writer = new MockRecordWriter(null, false)
        runner = TestRunners.newTestRunner(IdentifyLanguageRecord.class)
        runner.addControllerService("reader", reader)
        runner.addControllerService("writer", writer)
        runner.setProperty(IdentifyLanguageRecord.READER, "reader")
        runner.setProperty(IdentifyLanguageRecord.WRITER, "writer")
        runner.setProperty(IdentifyLanguageRecord.INPUT_RECORD_PATH, "/phrase")
        runner.setProperty(IdentifyLanguageRecord.OUTPUT_RECORD_PATH, "/language")
        runner.setProperty(IdentifyLanguageRecord.UNKNOWN_LANGUAGE, "en")
        runner.enableControllerService(reader)
        runner.enableControllerService(writer)
        runner.assertValid()
    }

    @Test
    void testNormal() {
        runner.enqueue("")
        runner.run()

        runner.assertTransferCount(IdentifyLanguageRecord.REL_FAILURE, 0)
        runner.assertTransferCount(IdentifyLanguageRecord.REL_ORIGINAL, 1)
        runner.assertTransferCount(IdentifyLanguageRecord.REL_SUCCESS, 1)

        def output = runner.getFlowFilesForRelationship(IdentifyLanguageRecord.REL_SUCCESS)[0]
        def raw = runner.getContentAsByteArray(output)
        def content = new String(raw)
        def lines = content.split("\n").collect { it.trim().split(",") }
        assert lines && lines.size() == 3
        assert lines[0][0] == SPANISH_SAMPLE
        assert lines[0][1] == 'es'
        assert lines[1][0] == ENGLISH_SAMPLE
        assert lines[1][1] == 'en'
        assert lines[2][0] == UNKNOWN_SAMPLE
        assert lines[2][1] == 'en'
    }
}
