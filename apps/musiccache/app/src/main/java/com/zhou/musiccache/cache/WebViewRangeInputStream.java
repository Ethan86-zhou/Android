package com.zhou.musiccache.cache;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 适配Chromium的shouldInterceptRequest流接口，不是普通HTTP响应流。
 *
 * Chromium InputStreamReader在读取WebResourceResponse头之前，先用available()校验原请求
 * Range，再skip(原文件起点)。HTTP代理的206正文已经从该起点开始，必须确认这次初始化定位，
 * 不能再次丢弃正文。这里不生成前缀字节，不读取未请求区间，也不计入任何虚构下载/命中字节。
 * 来源：components/embedder_support/android/util/input_stream_reader.cc，Seek/VerifyRequestedRange。
 *
 * 此类只能在WebView入口包裹已经裁剪好的HTTP正文。普通HTTP/JVM读流应直接使用原始正文。
 */
final class WebViewRangeInputStream extends FilterInputStream {
    private final int initialAvailable;
    private long initializationSkip;
    private long bodyRemaining;
    private boolean reading;
    private boolean initializedBySkip;

    WebViewRangeInputStream(InputStream body, long representationLength, long skipToAcknowledge, long bodyLength) {
        super(body);
        // JNI available是有符号int。超大/未知长度保持0，不把截断整数当成完整文件长度。
        initialAvailable = representationLength > 0 && representationLength <= Integer.MAX_VALUE
                ? (int) representationLength : 0;
        initializationSkip = Math.max(0, skipToAcknowledge);
        bodyRemaining = bodyLength;
    }

    @Override public int available() throws IOException {
        if (!reading && !initializedBySkip) return initialAvailable;
        int available = in.available();
        return bodyRemaining >= 0 ? (int) Math.min(available, Math.min(bodyRemaining, Integer.MAX_VALUE)) : available;
    }

    @Override public long skip(long count) throws IOException {
        if (count <= 0) return 0;
        if (!reading && initializationSkip > 0) {
            long acknowledged = Math.min(count, initializationSkip);
            initializationSkip -= acknowledged;
            if (initializationSkip == 0) initializedBySkip = true;
            return acknowledged;
        }
        // 后续真正跳过正文时通过read消耗，保持NetworkStream/命中计数和事务行为准确。
        byte[] discard = new byte[(int) Math.min(8192, count)];
        long consumed = 0;
        while (consumed < count) {
            int read = read(discard, 0, (int) Math.min(discard.length, count - consumed));
            if (read < 0) break;
            if (read == 0) break;
            consumed += read;
        }
        return consumed;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] buffer, int offset, int count) throws IOException {
        if (count == 0) return 0;
        // 若后续WebView不再预先seek，直接read也应得到HTTP正文，而不是补零的虚构前缀。
        reading = true; initializationSkip = 0;
        int read = in.read(buffer, offset, count);
        if (read > 0 && bodyRemaining >= 0) bodyRemaining = Math.max(0, bodyRemaining - read);
        return read;
    }
}
