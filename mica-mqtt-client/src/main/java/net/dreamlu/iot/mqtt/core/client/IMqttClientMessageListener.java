/*
 * Copyright (c) 2019-2029, Dreamlu 卢春梦 (596392912@qq.com & dreamlu.net).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dreamlu.iot.mqtt.core.client;

import net.dreamlu.iot.mqtt.codec.MqttPublishMessage;
import net.dreamlu.iot.mqtt.codec.MqttQoS;
import net.dreamlu.iot.mqtt.codec.MqttSubAckMessage;
import org.tio.core.ChannelContext;

import java.nio.ByteBuffer;

/**
 * mqtt 消息处理
 *
 * @author L.cm
 */
@FunctionalInterface
public interface IMqttClientMessageListener {

	/**
	 * 订阅成功之后的事件
	 *
	 * @param context     ChannelContext
	 * @param topicFilter topicFilter
	 * @param mqttQoS     MqttQoS
	 * @param message     MqttSubAckMessage
	 */
	default void onSubscribed(ChannelContext context, String topicFilter, MqttQoS mqttQoS, MqttSubAckMessage message) {
		onSubscribed(context, topicFilter, mqttQoS);
	}

	/**
	 * 订阅成功之后的事件
	 *
	 * @param context     ChannelContext
	 * @param topicFilter topicFilter
	 * @param mqttQoS     MqttQoS
	 */
	default void onSubscribed(ChannelContext context, String topicFilter, MqttQoS mqttQoS) {

	}

	/**
	 * 监听到消息
	 *
	 * @param context ChannelContext
	 * @param topic   topic
	 * @param message MqttPublishMessage
	 * @param payload payload
	 */
	void onMessage(ChannelContext context, String topic, MqttPublishMessage message, ByteBuffer payload);

}
