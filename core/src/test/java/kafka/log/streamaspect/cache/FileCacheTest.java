/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log.streamaspect.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("S3Unit")
public class FileCacheTest {

    @Test
    public void test() throws IOException {
        FileCache fileCache = new FileCache("/tmp/file_cache_test", 10 * 1024, 1024);

        // occupy block 0,1
        long streamId1 = 1;
        fileCache.put(streamId1, 10, genBuf((byte) 1, 2 * 1024));

        ByteBuf rst = fileCache.get(streamId1, 10 + 1000, 1024).get();
        assertEquals(1024, rst.readableBytes());
        assertTrue(verify(rst, (byte) 1));

        Assertions.assertFalse(fileCache.get(streamId1, 10 + 1000, 2048).isPresent());

        // occupy block 2,3
        long streamId2 = 2;
        fileCache.put(streamId2, 233, genBuf((byte) 2, 1025));

        // occupy block 4~8
        fileCache.put(streamId2, 2048, genBuf((byte) 4, 1024 * 5));

        // occupy block 9
        fileCache.put(streamId2, 10000, genBuf((byte) 5, 1024));

        // touch lru
        assertEquals(1025, fileCache.get(streamId2, 233, 1025).get().readableBytes());
        assertEquals(1024, fileCache.get(streamId2, 10000, 1024).get().readableBytes());
        assertEquals(2048, fileCache.get(streamId1, 10, 2048).get().readableBytes());
        assertEquals(1024 * 5, fileCache.get(streamId2, 2048, 1024 * 5).get().readableBytes());

        // expect evict test2-233 and test2-10000
        long streamId3 = 3;
        fileCache.put(streamId3, 123, genBuf((byte) 6, 2049));

        FileCache.Value value = fileCache.stream2cache.get(streamId3).get(123L);
        assertEquals(2049, value.dataLength);
        assertArrayEquals(new int[] {2, 3, 9}, value.blocks);

        rst = fileCache.get(streamId3, 123, 2049).get();
        assertEquals(2049, rst.readableBytes());
        assertTrue(verify(rst, (byte) 6));

        // expect evict test1-10 and test2-2048
        long streamId4 = 4;
        fileCache.put(streamId4, 123, genBuf((byte) 7, 2049));
        value = fileCache.stream2cache.get(streamId4).get(123L);
        assertArrayEquals(new int[] {0, 1, 4}, value.blocks);
        rst = fileCache.get(streamId4, 123, 2049).get();
        assertTrue(verify(rst, (byte) 7));

        assertEquals(4, fileCache.freeBlockCount);

        // expect occupy free blocks 5,6,7
        long streamId5 = 5;
        fileCache.put(streamId5, 123, genBuf((byte) 8, 2049));
        value = fileCache.stream2cache.get(streamId5).get(123L);
        assertArrayEquals(new int[] {5, 6, 7}, value.blocks);
        rst = fileCache.get(streamId5, 123, 2049).get();
        assertTrue(verify(rst, (byte) 8));
        assertEquals(1, fileCache.freeBlockCount);

        long streamId6 = 6;
        fileCache.put(streamId6, 6666, genBuf((byte) 9, 3333));
        rst = fileCache.get(streamId6, 6666L, 3333).get();
        assertTrue(verify(rst, (byte) 9));

    }

    @Test
    public void testMergePut() throws IOException {
        FileCache fileCache = new FileCache("/tmp/file_cache_test", 10 * 1024, 1024);
        long streamId = 1;
        CompositeByteBuf buf;
        {
            buf = Unpooled.compositeBuffer();
            buf.addComponent(true, genBuf((byte) 1, 500));
            buf.addComponent(true, genBuf((byte) 2, 500));
            buf.addComponent(true, genBuf((byte) 3, 500));
            fileCache.put(streamId, 3333L, buf);
        }
        assertEquals(1, fileCache.stream2cache.get(streamId).size());
        assertEquals(1500, fileCache.stream2cache.get(streamId).get(3333L).dataLength);
        assertTrue(verify(fileCache.get(streamId, 3333L, 500).get(), (byte) 1));
        assertTrue(verify(fileCache.get(streamId, 3333L + 500, 500).get(), (byte) 2));
        assertTrue(verify(fileCache.get(streamId, 3333L + 1000, 500).get(), (byte) 3));

        {
            buf = Unpooled.compositeBuffer();
            buf.addComponent(true, genBuf((byte) 2, 500));
            fileCache.put(streamId, 3333L + 500, buf);
        }
        assertEquals(1, fileCache.stream2cache.get(streamId).size());
        assertEquals(1500, fileCache.stream2cache.get(streamId).get(3333L).dataLength);
        assertTrue(verify(fileCache.get(streamId, 3333L, 500).get(), (byte) 1));
        assertTrue(verify(fileCache.get(streamId, 3333L + 500, 500).get(), (byte) 2));
        assertTrue(verify(fileCache.get(streamId, 3333L + 1000, 500).get(), (byte) 3));

        {
            buf = Unpooled.compositeBuffer();
            buf.addComponent(true, genBuf((byte) 4, 500));
            buf.addComponent(true, genBuf((byte) 5, 500));
            buf.addComponent(true, genBuf((byte) 6, 500));
            fileCache.put(streamId, 3333L + 1000, buf);
        }
        assertEquals(1, fileCache.stream2cache.get(streamId).size());
        assertEquals(2500, fileCache.stream2cache.get(streamId).get(3333L).dataLength);
        assertTrue(verify(fileCache.get(streamId, 3333L, 500).get(), (byte) 1));
        assertTrue(verify(fileCache.get(streamId, 3333L + 500, 500).get(), (byte) 2));
        assertTrue(verify(fileCache.get(streamId, 3333L + 1000, 500).get(), (byte) 4));
        assertTrue(verify(fileCache.get(streamId, 3333L + 1500, 500).get(), (byte) 5));
        assertTrue(verify(fileCache.get(streamId, 3333L + 2000, 500).get(), (byte) 6));

        {
            buf = Unpooled.compositeBuffer();
            buf.addComponent(true, genBuf((byte) 7, 500));
            fileCache.put(streamId, 3333L + 1000 + 1500, buf);
        }
        assertEquals(1, fileCache.stream2cache.get(streamId).size());
        assertEquals(3000, fileCache.stream2cache.get(streamId).get(3333L).dataLength);

        assertTrue(verify(fileCache.get(streamId, 3333L, 500).get(), (byte) 1));
        assertTrue(verify(fileCache.get(streamId, 3333L + 500, 500).get(), (byte) 2));
        assertTrue(verify(fileCache.get(streamId, 3333L + 1000, 500).get(), (byte) 4));
        assertTrue(verify(fileCache.get(streamId, 3333L + 1500, 500).get(), (byte) 5));
        assertTrue(verify(fileCache.get(streamId, 3333L + 2000, 500).get(), (byte) 6));
        assertTrue(verify(fileCache.get(streamId, 3333L + 2500, 500).get(), (byte) 7));
    }

    ByteBuf genBuf(byte data, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, data);
        return Unpooled.wrappedBuffer(bytes);
    }

    boolean verify(ByteBuf buf, byte data) {
        buf = buf.duplicate();
        while (buf.readableBytes() > 0) {
            if (buf.readByte() != data) {
                return false;
            }
        }
        return true;
    }

}
