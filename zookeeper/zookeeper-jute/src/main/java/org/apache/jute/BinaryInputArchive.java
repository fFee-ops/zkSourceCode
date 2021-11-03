/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jute;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * BinaryInputArchive是对于DataInput的封装，用于处理基于zookeeper协议的底层数据,其实就是从DataInputStream输入中读取数据。
 */
public class BinaryInputArchive implements InputArchive {

    public static final String UNREASONBLE_LENGTH = "Unreasonable length = ";

    // CHECKSTYLE.OFF: ConstantName - for backward compatibility
    public static final int maxBuffer = Integer.getInteger("jute.maxbuffer", 0xfffff);
    // CHECKSTYLE.ON:
    private static final int extraMaxBuffer;

    static {
        final Integer configuredExtraMaxBuffer =
            Integer.getInteger("zookeeper.jute.maxbuffer.extrasize", maxBuffer);
        if (configuredExtraMaxBuffer < 1024) {
            // Earlier hard coded value was 1024, So the value should not be less than that value
            extraMaxBuffer = 1024;
        } else {
            extraMaxBuffer = configuredExtraMaxBuffer;
        }
    }

    // DataInput接口，用于从二进制流中读取字节
    private DataInput in;
    private int maxBufferSize;
    private int extraMaxBufferSize;

    // 静态方法，用于获取BinaryInputArchive
    public static BinaryInputArchive getArchive(InputStream strm) {
        return new BinaryInputArchive(new DataInputStream(strm));
    }

    /***
     * 内部类，对应BinaryInputArchive索引
     */
    private static class BinaryIndex implements Index {
        private int nelems;

        BinaryIndex(int nelems) {
            this.nelems = nelems;
        }

        @Override
        public boolean done() {
            return (nelems <= 0);
        }

        @Override
        public void incr() {
            nelems--;
        }
    }

    /**
     * 创建BinaryInputArchive实例
     */
    public BinaryInputArchive(DataInput in) {
        this(in, maxBuffer, extraMaxBuffer);
    }

    /****
     * 创建BinaryInputArchive实例，同时限制读取数据大小，maxBufferSize+extraMaxBufferSize=缓冲区最大大小
     * @param in
     * @param maxBufferSize：最大缓冲区大小
     * @param extraMaxBufferSize：额外缓冲区大小
     */
    public BinaryInputArchive(DataInput in, int maxBufferSize, int extraMaxBufferSize) {
        this.in = in;
        this.maxBufferSize = maxBufferSize;
        this.extraMaxBufferSize = extraMaxBufferSize;
    }

    /***
     * 读取byte数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public byte readByte(String tag) throws IOException {
        return in.readByte();
    }

    /***
     * 读取boolean数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public boolean readBool(String tag) throws IOException {
        return in.readBoolean();
    }

    /***
     * 读取int数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public int readInt(String tag) throws IOException {
        return in.readInt();
    }

    /***
     * 读取long数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public long readLong(String tag) throws IOException {
        return in.readLong();
    }

    /***
     * 读取float数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public float readFloat(String tag) throws IOException {
        return in.readFloat();
    }

    /***
     * 读取dubbo数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public double readDouble(String tag) throws IOException {
        return in.readDouble();
    }

    /***
     * 读取String数据
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public String readString(String tag) throws IOException {
        int len = in.readInt();
        if (len == -1) {
            return null;
        }
        //校验长度
        checkLength(len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /***
     * 读取byte数组
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public byte[] readBuffer(String tag) throws IOException {
        int len = readInt(tag);
        if (len == -1) {
            return null;
        }
        //长度校验
        checkLength(len);
        byte[] arr = new byte[len];
        in.readFully(arr);
        return arr;
    }

    /***
     * 反序列化操作
     * @param r
     * @param tag
     * @throws IOException
     */
    @Override
    public void readRecord(Record r, String tag) throws IOException {
        r.deserialize(this, tag);
    }

    /***
     * 开始读取数据
     * @param tag
     * @throws IOException
     */
    @Override
    public void startRecord(String tag) throws IOException {
    }

    /***
     * 结束读取数据
     * @param tag
     * @throws IOException
     */
    @Override
    public void endRecord(String tag) throws IOException {
    }

    /****
     * 开始读取向量
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public Index startVector(String tag) throws IOException {
        int len = readInt(tag);
        if (len == -1) {
            return null;
        }
        //创建索引
        return new BinaryIndex(len);
    }

    /***
     * 结束读取向量
     * @param tag
     * @throws IOException
     */
    @Override
    public void endVector(String tag) throws IOException {
    }

    /***
     * 开始读取Map
     * @param tag
     * @return
     * @throws IOException
     */
    @Override
    public Index startMap(String tag) throws IOException {
        //读取数据并创建索引
        return new BinaryIndex(readInt(tag));
    }

    /***
     * 结束读取Map
     * @param tag
     * @throws IOException
     */
    @Override
    public void endMap(String tag) throws IOException {
    }

    // Since this is a rough sanity check, add some padding to maxBuffer to
    // make up for extra fields, etc. (otherwise e.g. clients may be able to
    // write buffers larger than we can read from disk!)
    //读取数据最大大小判断
    private void checkLength(int len) throws IOException {
        if (len < 0 || len > maxBufferSize + extraMaxBufferSize) {
            throw new IOException(UNREASONBLE_LENGTH + len);
        }
    }
}
