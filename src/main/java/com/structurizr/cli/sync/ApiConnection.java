package com.structurizr.cli.sync;

public class ApiConnection {
    public String url;
    public String apiKeyPlainText;

    public ApiConnection(String url, String apiKeyPlainText) {
        this.url = url;
        this.apiKeyPlainText = apiKeyPlainText;
    }
}
