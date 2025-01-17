/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.snapshots;

import org.elasticsearch.ElasticsearchCorruptionException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobMetadata;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.blobstore.fs.FsBlobStore;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentFragment;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParserUtils;
import org.elasticsearch.index.translog.BufferedChecksumStreamOutput;
import org.elasticsearch.repositories.blobstore.ChecksumBlobStoreFormat;
import org.elasticsearch.test.ESTestCase;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;

public class BlobStoreFormatTests extends ESTestCase {

    public static final String BLOB_CODEC = "blob";

    private static class BlobObj implements ToXContentFragment {

        private final String text;

        BlobObj(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public static BlobObj fromXContent(XContentParser parser) throws IOException {
            String text = null;
            XContentParser.Token token = parser.currentToken();
            if (token == null) {
                token = parser.nextToken();
            }
            if (token == XContentParser.Token.START_OBJECT) {
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    XContentParserUtils.ensureFieldName(parser, token, "text");
                    XContentParserUtils.ensureExpectedToken(XContentParser.Token.VALUE_STRING, parser.nextToken(), parser);
                    text = parser.text();
                }
            }
            if (text == null) {
                throw new ElasticsearchParseException("missing mandatory parameter text");
            }
            return new BlobObj(text);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
            builder.field("text", getText());
            return builder;
        }
    }

    public void testBlobStoreOperations() throws IOException {
        BlobStore blobStore = createTestBlobStore();
        BlobContainer blobContainer = blobStore.blobContainer(BlobPath.EMPTY);
        ChecksumBlobStoreFormat<BlobObj> checksumSMILE = new ChecksumBlobStoreFormat<>(BLOB_CODEC, "%s", BlobObj::fromXContent);

        // Write blobs in different formats
        final String randomText = randomAlphaOfLengthBetween(0, 1024 * 8 * 3);
        final String normalText = "checksum smile: " + randomText;
        checksumSMILE.write(new BlobObj(normalText), blobContainer, "check-smile", false, MockBigArrays.NON_RECYCLING_INSTANCE);
        final String compressedText = "checksum smile compressed: " + randomText;
        checksumSMILE.write(new BlobObj(compressedText), blobContainer, "check-smile-comp", true,
                MockBigArrays.NON_RECYCLING_INSTANCE);

        // Assert that all checksum blobs can be read
        assertEquals(normalText, checksumSMILE.read(blobContainer, "check-smile", xContentRegistry()).getText());
        assertEquals(compressedText, checksumSMILE.read(blobContainer, "check-smile-comp", xContentRegistry()).getText());
    }

    public void testCompressionIsApplied() throws IOException {
        BlobStore blobStore = createTestBlobStore();
        BlobContainer blobContainer = blobStore.blobContainer(BlobPath.EMPTY);
        StringBuilder veryRedundantText = new StringBuilder();
        for (int i = 0; i < randomIntBetween(100, 300); i++) {
            veryRedundantText.append("Blah ");
        }
        ChecksumBlobStoreFormat<BlobObj> checksumFormat = new ChecksumBlobStoreFormat<>(BLOB_CODEC, "%s", BlobObj::fromXContent);
        BlobObj blobObj = new BlobObj(veryRedundantText.toString());
        checksumFormat.write(blobObj, blobContainer, "blob-comp", true, MockBigArrays.NON_RECYCLING_INSTANCE);
        checksumFormat.write(blobObj, blobContainer, "blob-not-comp", false, MockBigArrays.NON_RECYCLING_INSTANCE);
        Map<String, BlobMetadata> blobs = blobContainer.listBlobsByPrefix("blob-");
        assertEquals(blobs.size(), 2);
        assertThat(blobs.get("blob-not-comp").length(), greaterThan(blobs.get("blob-comp").length()));
    }

    public void testBlobCorruption() throws IOException {
        BlobStore blobStore = createTestBlobStore();
        BlobContainer blobContainer = blobStore.blobContainer(BlobPath.EMPTY);
        String testString = randomAlphaOfLength(randomInt(10000));
        BlobObj blobObj = new BlobObj(testString);
        ChecksumBlobStoreFormat<BlobObj> checksumFormat = new ChecksumBlobStoreFormat<>(BLOB_CODEC, "%s", BlobObj::fromXContent);
        checksumFormat.write(blobObj, blobContainer, "test-path", randomBoolean(), MockBigArrays.NON_RECYCLING_INSTANCE);
        assertEquals(checksumFormat.read(blobContainer, "test-path", xContentRegistry()).getText(),
                testString);
        randomCorruption(blobContainer, "test-path");
        try {
            checksumFormat.read(blobContainer, "test-path", xContentRegistry());
            fail("Should have failed due to corruption");
        } catch (ElasticsearchCorruptionException | EOFException ex) {
            // expected exceptions from random byte corruption
        }
    }

    protected BlobStore createTestBlobStore() throws IOException {
        return new FsBlobStore(randomIntBetween(1, 8) * 1024, createTempDir(), false);
    }

    protected void randomCorruption(BlobContainer blobContainer, String blobName) throws IOException {
        byte[] buffer = new byte[(int) blobContainer.listBlobsByPrefix(blobName).get(blobName).length()];
        long originalChecksum = checksum(buffer);
        try (InputStream inputStream = blobContainer.readBlob(blobName)) {
            Streams.readFully(inputStream, buffer);
        }
        do {
            int location = randomIntBetween(0, buffer.length - 1);
            buffer[location] = (byte) (buffer[location] ^ 42);
        } while (originalChecksum == checksum(buffer));
        blobContainer.writeBlob(blobName, new BytesArray(buffer), false);
    }

    private long checksum(byte[] buffer) throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput()) {
            try (BufferedChecksumStreamOutput checksumOutput = new BufferedChecksumStreamOutput(streamOutput)) {
                checksumOutput.write(buffer);
                return checksumOutput.getChecksum();
            }
        }
    }
}
