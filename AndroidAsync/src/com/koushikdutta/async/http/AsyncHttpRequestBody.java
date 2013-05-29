package com.koushikdutta.async.http;

import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;

public interface AsyncHttpRequestBody<T> {
    public void write(AsyncHttpRequest request, AsyncHttpResponse sink);
    public void parse(DataEmitter emitter, CompletedCallback completed);
    public String getContentType();
    public boolean readFullyOnRequest();
    public int length();
    public T get();
}
