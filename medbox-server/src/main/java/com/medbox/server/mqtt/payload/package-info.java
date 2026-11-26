/**
 * 上行消息的 payload DTO（字段与文档 03 第 3 章的示例逐字一致；遥测只带读数，不带传感器来源 ID）。
 *
 * <p>这里只做"消息形状"的抽象，**不含任何业务处理**；反序列化后怎么用由各业务分支决定。
 *
 * <p>不写 {@code @JsonIgnoreProperties}：Boot 默认已关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 固件后续新增字段不会把反序列化打断。
 */
package com.medbox.server.mqtt.payload;
