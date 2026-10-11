package com.medbox.server.mqtt;

/**
 * MQTT 通道的运行时异常：把 Paho 的受检 {@code MqttException} 包一层，
 * 免得每个调用方都被迫 try/catch 一个协议库特有的受检异常。
 */
public class MqttTransportException extends RuntimeException {

    public MqttTransportException(String message) {
        super(message);
    }

    public MqttTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
