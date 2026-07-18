package com.jobpilot.manualurl.fetch;

public record ManualHttpResponse(
        int statusCode,
        String contentType,
        String location,
        byte[] body) {

    public ManualHttpResponse {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
