package com.example.livetranscription.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientMessage {
    private String type;
    private Object data;
    private String message; // For error messages

    public ClientMessage() {}

    public ClientMessage(String type, Object data) {
        this.type = type;
        this.data = data;
    }

    public static ClientMessage error(String message) {
        ClientMessage cm = new ClientMessage();
        cm.setType("proxy_error");
        cm.setMessage(message);
        return cm;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
