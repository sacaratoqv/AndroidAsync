package com.koushikdutta.async.http;

import java.net.URI;
import java.net.URISyntaxException;

public class AsyncHttpPost extends AsyncHttpRequest {
    public static final String METHOD = "POST";
    
    public AsyncHttpPost(String uri) throws URISyntaxException {
        this(new URI(uri));
    }

    public AsyncHttpPost(URI uri) {
        super(uri, METHOD);
    }
    
    @Override
    protected void onConnect(AsyncHttpResponse response) {
    }
}
