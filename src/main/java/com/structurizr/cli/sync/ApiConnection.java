package com.structurizr.cli.sync;

public class ApiConnection {
    public String url;
    public String apiKeyPlainText;
    public String apiKeyBCrypt;

    public ApiConnection(String url, String apiKeyPlainText, String apiKeyBCrypt) {
        this.url = url;
        this.apiKeyPlainText = apiKeyPlainText;
        this.apiKeyBCrypt = apiKeyBCrypt;
    }
}
