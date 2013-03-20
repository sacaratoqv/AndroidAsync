package com.koushikdutta.async.http;

import junit.framework.Assert;
import android.util.Log;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.FilteredDataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.http.filter.ChunkedInputFilter;
import com.koushikdutta.async.http.filter.GZIPInputFilter;
import com.koushikdutta.async.http.filter.InflaterInputFilter;
import com.koushikdutta.async.http.libcore.RawHeaders;
import com.koushikdutta.async.http.server.UnknownRequestBody;

public class Util {
    public static AsyncHttpRequestBody getBody(DataEmitter emitter, final CompletedCallback reporter, RawHeaders headers) {
        String contentType = headers.get("Content-Type");
        if (contentType != null) {
            String[] values = contentType.split(";");
            for (int i = 0; i < values.length; i++) {
                values[i] = values[i].trim();
            }
            for (String ct: values) {
                if (UrlEncodedFormBody.CONTENT_TYPE.equals(ct))
                    return new UrlEncodedFormBody();
                if (MultipartFormDataBody.CONTENT_TYPE.equals(ct)) {
                    MultipartFormDataBody ret = new MultipartFormDataBody(contentType, values);
                    ret.setDataEmitter(emitter);
                    ret.setEndCallback(null);
                    emitter.setEndCallback(reporter);
                    return ret;
                }
            }
        }
        return new UnknownRequestBody(contentType);
    }
    
    public static DataEmitter getBodyDecoder(DataEmitter emitter, RawHeaders headers, boolean server, final CompletedCallback reporter) {
        int _contentLength;
        try {
            _contentLength = Integer.parseInt(headers.get("Content-Length"));
        }
        catch (Exception ex) {
            _contentLength = -1;
        }
        final int contentLength = _contentLength;
        if (-1 != contentLength) {
            if (contentLength < 0) {
                emitter.getServer().post(new Runnable() {
                    @Override
                    public void run() {
                        reporter.onCompleted(new Exception("not using chunked encoding, and no content-length found."));
                    }
                });
                return emitter;
            }
            if (contentLength == 0) {
                emitter.getServer().post(new Runnable() {
                    @Override
                    public void run() {
                        reporter.onCompleted(null);
                    }
                });
                return emitter;
            }
            FilteredDataEmitter contentLengthWatcher = new FilteredDataEmitter() {
                int totalRead = 0;
                @Override
                public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                    Assert.assertTrue(totalRead < contentLength);
                    ByteBufferList list = bb.get(Math.min(contentLength - totalRead, bb.remaining()));
                    totalRead += list.remaining();
                    super.onDataAvailable(emitter, list);
                    if (totalRead == contentLength)
                        report(null);
                }
            };
            contentLengthWatcher.setDataEmitter(emitter);
            contentLengthWatcher.setEndCallback(reporter);
            emitter = contentLengthWatcher;
        }
        else if ("chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"))) {
            ChunkedInputFilter chunker = new ChunkedInputFilter();
            chunker.setDataEmitter(emitter);
            chunker.setEndCallback(reporter);
            emitter = chunker;
        }
        else if (server) {
            // if this is the server, and the client has not indicated a request body, the client is done
            emitter.getServer().post(new Runnable() {
                @Override
                public void run() {
                    reporter.onCompleted(null);
                }
            });
        }

        if ("gzip".equals(headers.get("Content-Encoding"))) {
            GZIPInputFilter gunzipper = new GZIPInputFilter();
            gunzipper.setDataEmitter(emitter);
            emitter = gunzipper;
        }        
        else if ("deflate".equals(headers.get("Content-Encoding"))) {
            InflaterInputFilter inflater = new InflaterInputFilter();
            inflater.setDataEmitter(emitter);
            emitter = inflater;
        }

        // conversely, if this is the client, and the server has not indicated a request body, we do not report
        // the close/end event until the server actually closes the connection.
        return emitter;
    }
}
