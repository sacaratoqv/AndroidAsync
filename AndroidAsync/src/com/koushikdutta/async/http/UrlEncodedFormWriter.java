package com.koushikdutta.async.http;

import java.net.URLEncoder;
import java.util.List;

import org.apache.http.NameValuePair;

import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;

public class UrlEncodedFormWriter implements AsyncHttpRequestContentWriter {
    List<NameValuePair> mParameters;
    public UrlEncodedFormWriter(List<NameValuePair> parameters) {
        mParameters = parameters;
    }
    @Override
    public void write(AsyncHttpRequest request, final AsyncHttpResponse response) {
        boolean first = true;
        StringBuilder b = new StringBuilder();
        for (NameValuePair pair: mParameters) {
            if (!first)
                b.append('&');
            first = false;
            b.append(URLEncoder.encode(pair.getName()));
            b.append('=');
            b.append(URLEncoder.encode(pair.getValue()));
        }
        byte[] bytes = b.toString().getBytes();
        Util.writeAll(response, bytes, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                response.end();
            }
        });
    }
    @Override
    public String getContentType() {
        return "application/x-www-form-urlencoded";
    }
}
