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

import net.dreamlu.iot.mqtt.codec.*;
import net.dreamlu.iot.mqtt.core.common.MqttPendingPublish;
import net.dreamlu.iot.mqtt.core.common.MqttPendingQos2Publish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tio.core.ChannelContext;
import org.tio.core.Node;
import org.tio.core.Tio;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 默认的 mqtt 消息处理器
 *
 * @author L.cm
 */
public class DefaultMqttClientProcessor implements IMqttClientProcessor {
	private static final Logger logger = LoggerFactory.getLogger(DefaultMqttClientProcessor.class);
	private final MqttClientStore clientStore;
	private final IMqttClientConnectListener connectListener;
	private final ScheduledThreadPoolExecutor executor;

	public DefaultMqttClientProcessor(MqttClientCreator mqttClientCreator,
									  MqttClientStore clientStore,
									  ScheduledThreadPoolExecutor executor) {
		this.clientStore = clientStore;
		this.connectListener = mqttClientCreator.getConnectListener();
		this.executor = executor;
	}

	@Override
	public void processDecodeFailure(ChannelContext context, MqttMessage message, Throwable ex) {
		// 客户端失败，默认记录异常日志
		logger.error(ex.getMessage(), ex);
	}

	@Override
	public void processConAck(ChannelContext context, MqttConnAckMessage message) {
		MqttConnAckVariableHeader connAckVariableHeader = message.variableHeader();
		MqttConnectReturnCode returnCode = connAckVariableHeader.connectReturnCode();
		switch (returnCode) {
			case CONNECTION_ACCEPTED:
				// 1. 连接成功的日志
				if (logger.isInfoEnabled()) {
					Node node = context.getServerNode();
					logger.info("MqttClient contextId:{} connection:{}:{} succeeded!", context.getId(), node.getIp(), node.getPort());
				}
				// 2. 发布连接通知
				publishConnectEvent(context);
				// 3. 如果 session 不存在重连时发送重新订阅
				if (!connAckVariableHeader.isSessionPresent()) {
					reSendSubscription(context);
				}
				break;
			case CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD:
			case CONNECTION_REFUSED_IDENTIFIER_REJECTED:
			case CONNECTION_REFUSED_NOT_AUTHORIZED:
			case CONNECTION_REFUSED_SERVER_UNAVAILABLE:
			case CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION:
			default:
				String remark = "MqttClient connect error error ReturnCode:" + returnCode;
				Tio.close(context, remark);
				break;
		}
	}

	private void reSendSubscription(ChannelContext context) {
		List<MqttClientSubscription> subscriptionList = clientStore.getAndCleanSubscription();
		for (MqttClientSubscription subscription : subscriptionList) {
			int messageId = MqttClientMessageId.getId();
			MqttQoS mqttQoS = subscription.getMqttQoS();
			String topicFilter = subscription.getTopicFilter();
			MqttSubscribeMessage message = MqttMessageBuilders.subscribe()
				.addSubscription(mqttQoS, topicFilter)
				.messageId(messageId)
				.build();
			MqttPendingSubscription pendingSubscription = new MqttPendingSubscription(mqttQoS, topicFilter, subscription.getListener(), message);
			Boolean result = Tio.send(context, message);
			logger.info("MQTT Topic:{} mqttQoS:{} messageId:{} resubscribing result:{}", topicFilter, mqttQoS, messageId, result);
			pendingSubscription.startRetransmitTimer(executor, (msg) -> Tio.send(context, message));
			clientStore.addPaddingSubscribe(messageId, pendingSubscription);
		}
	}

	private void publishConnectEvent(ChannelContext context) {
		// 先判断是否配置监听
		if (connectListener == null) {
			return;
		}
		try {
			connectListener.onConnected(context, context.isReconnect);
		} catch (Throwable e) {
			logger.error(e.getMessage(), e);
		}
	}

	@Override
	public void processSubAck(MqttSubAckMessage message) {
		int messageId = message.variableHeader().messageId();
		logger.debug("MqttClient SubAck messageId:{}", messageId);
		MqttPendingSubscription paddingSubscribe = clientStore.getPaddingSubscribe(messageId);
		if (paddingSubscribe == null) {
			return;
		}
		String topicFilter = paddingSubscribe.getTopicFilter();
		MqttSubAckPayload subAckPayload = message.payload();
		List<Integer> reasonCodes = subAckPayload.reasonCodes();
		// reasonCodes 为空
		if (reasonCodes.isEmpty()) {
			logger.error("MqttClient topicFilter:{} subscribe failed reasonCode is empty messageId:{}", topicFilter, messageId);
			paddingSubscribe.onSubAckReceived();
			clientStore.removePaddingSubscribe(messageId);
			return;
		}
		// reasonCodes 范围
		Integer qos = reasonCodes.get(0);
		if (qos == null || qos < 0 || qos > 2) {
			logger.error("MqttClient topicFilter:{} subscribe failed reasonCodes:{} messageId:{}", topicFilter, reasonCodes, messageId);
			paddingSubscribe.onSubAckReceived();
			clientStore.removePaddingSubscribe(messageId);
			return;
		}
		if (logger.isInfoEnabled()) {
			logger.info("MQTT Topic:{} successfully subscribed messageId:{}", topicFilter, messageId);
		}
		paddingSubscribe.onSubAckReceived();
		clientStore.removePaddingSubscribe(messageId);
		clientStore.addSubscription(paddingSubscribe.toSubscription());
	}

	@Override
	public void processPublish(ChannelContext context, MqttPublishMessage message) {
		MqttFixedHeader mqttFixedHeader = message.fixedHeader();
		MqttPublishVariableHeader variableHeader = message.variableHeader();
		String topicName = variableHeader.topicName();
		MqttQoS mqttQoS = mqttFixedHeader.qosLevel();
		int packetId = variableHeader.packetId();
		logger.debug("MqttClient received publish topic:{} qoS:{} packetId:{}", topicName, mqttQoS, packetId);
		switch (mqttFixedHeader.qosLevel()) {
			case AT_MOST_ONCE:
				invokeListenerForPublish(topicName, message);
				break;
			case AT_LEAST_ONCE:
				boolean result = invokeListenerForPublish(topicName, message);
				if (packetId != -1 && result) {
					MqttMessage messageAck = MqttMessageBuilders.pubAck()
						.packetId(packetId)
						.build();
					Tio.send(context, messageAck);
				}
				break;
			case EXACTLY_ONCE:
				if (packetId != -1) {
					MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0);
					MqttMessage pubRecMessage = new MqttMessage(fixedHeader, MqttMessageIdVariableHeader.from(packetId));
					MqttPendingQos2Publish pendingQos2Publish = new MqttPendingQos2Publish(message, pubRecMessage);
					clientStore.addPendingQos2Publish(packetId, pendingQos2Publish);
					pendingQos2Publish.startPubRecRetransmitTimer(executor, msg -> Tio.send(context, msg));
				}
				break;
			case FAILURE:
			default:
		}
	}

	@Override
	public void processUnSubAck(MqttUnsubAckMessage message) {
		int messageId = message.variableHeader().messageId();
		logger.debug("MqttClient UnSubAck messageId:{}", messageId);
		MqttPendingUnSubscription pendingUnSubscription = clientStore.getPaddingUnSubscribe(messageId);
		if (pendingUnSubscription == null) {
			return;
		}
		if (logger.isInfoEnabled()) {
			logger.info("MQTT Topic:{} successfully unSubscribed  messageId:{}", pendingUnSubscription.getTopic(), messageId);
		}
		pendingUnSubscription.onUnSubAckReceived();
		clientStore.removePaddingUnSubscribe(messageId);
		clientStore.removeSubscriptions(pendingUnSubscription.getTopic());
	}

	@Override
	public void processPubAck(MqttPubAckMessage message) {
		int messageId = message.variableHeader().messageId();
		logger.debug("MqttClient PubAck messageId:{}", messageId);
		MqttPendingPublish pendingPublish = clientStore.getPendingPublish(messageId);
		if (pendingPublish == null) {
			return;
		}
		if (logger.isInfoEnabled()) {
			String topicName = pendingPublish.getMessage().variableHeader().topicName();
			logger.info("MQTT Topic:{} successfully PubAck messageId:{}", topicName, messageId);
		}
		pendingPublish.onPubAckReceived();
		clientStore.removePendingPublish(messageId);
		pendingPublish.getPayload().clear();
	}

	@Override
	public void processPubRec(ChannelContext context, MqttMessage message) {
		int messageId = ((MqttMessageIdVariableHeader) message.variableHeader()).messageId();
		logger.debug("MqttClient PubRec messageId:{}", messageId);
		MqttPendingPublish pendingPublish = clientStore.getPendingPublish(messageId);
		pendingPublish.onPubAckReceived();

		MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0);
		MqttMessageIdVariableHeader variableHeader = (MqttMessageIdVariableHeader) message.variableHeader();
		MqttMessage pubRelMessage = new MqttMessage(fixedHeader, variableHeader);
		Tio.send(context, pubRelMessage);

		pendingPublish.setPubRelMessage(pubRelMessage);
		pendingPublish.startPubRelRetransmissionTimer(executor, msg -> Tio.send(context, msg));
	}

	@Override
	public void processPubRel(ChannelContext context, MqttMessage message) {
		int messageId = ((MqttMessageIdVariableHeader) message.variableHeader()).messageId();
		logger.debug("MqttClient PubRel messageId:{}", messageId);
		MqttPendingQos2Publish pendingQos2Publish = clientStore.getPendingQos2Publish(messageId);
		if (pendingQos2Publish != null) {
			MqttPublishMessage incomingPublish = pendingQos2Publish.getIncomingPublish();
			String topicName = incomingPublish.variableHeader().topicName();
			boolean result = this.invokeListenerForPublish(topicName, incomingPublish);
			if (result) {
				pendingQos2Publish.onPubRelReceived();
				clientStore.removePendingQos2Publish(messageId);
			}
		}
		MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBCOMP, false, MqttQoS.AT_MOST_ONCE, false, 0);
		MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(messageId);
		Tio.send(context, new MqttMessage(fixedHeader, variableHeader));
	}

	@Override
	public void processPubComp(MqttMessage message) {
		int messageId = ((MqttMessageIdVariableHeader) message.variableHeader()).messageId();
		MqttPendingPublish pendingPublish = clientStore.getPendingPublish(messageId);
		if (pendingPublish == null) {
			return;
		}
		if (logger.isInfoEnabled()) {
			String topicName = pendingPublish.getMessage().variableHeader().topicName();
			logger.info("MQTT Topic:{} successfully PubComp", topicName);
		}
		pendingPublish.getPayload().clear();
		pendingPublish.onPubCompReceived();
		clientStore.removePendingPublish(messageId);
	}

	/**
	 * 处理订阅的消息
	 *
	 * @param topicName topicName
	 * @param message   MqttPublishMessage
	 */
	private boolean invokeListenerForPublish(String topicName, MqttPublishMessage message) {
		List<MqttClientSubscription> subscriptionList = clientStore.getMatchedSubscription(topicName);
		final ByteBuffer payload = message.payload();
		for (MqttClientSubscription subscription : subscriptionList) {
			IMqttClientMessageListener listener = subscription.getListener();
			payload.rewind();
			try {
				listener.onMessage(topicName, payload);
			} catch (Throwable e) {
				logger.error(e.getMessage(), e);
				return false;
			}
		}
		return true;
	}

}
