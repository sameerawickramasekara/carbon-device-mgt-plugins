/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.iot.arduino.service.transport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.iot.arduino.plugin.constants.ArduinoConstants;
import org.wso2.carbon.device.mgt.iot.arduino.service.ArduinoService;
import org.wso2.carbon.device.mgt.iot.config.server.DeviceManagementConfigurationManager;
import org.wso2.carbon.device.mgt.iot.controlqueue.mqtt.MqttConfig;
import org.wso2.carbon.device.mgt.iot.controlqueue.mqtt.MqttSubscriber;

import java.io.File;
import java.util.LinkedList;
import java.util.UUID;

public class ArduinoMQTTSubscriber extends MqttSubscriber {
    private static Log log = LogFactory.getLog(ArduinoMQTTSubscriber.class);

    private static final String serverName =
            DeviceManagementConfigurationManager.getInstance().getDeviceManagementServerInfo().getName();
    private static final String subscribeTopic =
            serverName + File.separator + "+" + File.separator + ArduinoConstants.DEVICE_TYPE + File.separator + "#";


    private static final String iotServerSubscriber = UUID.randomUUID().toString().substring(0, 5);
    private static String mqttEndpoint;

    private ArduinoMQTTSubscriber() {
        super(iotServerSubscriber, ArduinoConstants.DEVICE_TYPE,
              MqttConfig.getInstance().getMqttQueueEndpoint(), subscribeTopic);
    }

    public void initConnector() {
        mqttEndpoint = MqttConfig.getInstance().getMqttQueueEndpoint();
    }

    public void connectAndSubscribe() {
        try {
            super.connectAndSubscribe();
        } catch (DeviceManagementException e) {
            log.error("Subscription to MQTT Broker at: " + mqttEndpoint + " failed");
            retryMQTTSubscription();
        }
    }

    @Override
    protected void postMessageArrived(String topic, MqttMessage message) {
        int lastIndex = topic.lastIndexOf("/");
        String deviceId = topic.substring(lastIndex + 1);

        lastIndex = message.toString().lastIndexOf(":");
        String msgContext = message.toString().substring(lastIndex + 1);

        LinkedList<String> deviceControlList = null;
        LinkedList<String> replyMessageList = null;

        if (msgContext.equals("IN") || msgContext.equals(ArduinoConstants.STATE_ON) || msgContext.equals(
                ArduinoConstants.STATE_OFF)) {

            if (log.isDebugEnabled()) {
                log.debug("Received a control message: ");
                log.debug("Control message topic: " + topic);
                log.debug("Control message: " + message.toString());
            }

            synchronized (ArduinoService.getInternalControlsQueue()) {
                deviceControlList = ArduinoService.getInternalControlsQueue().get(deviceId);
                if (deviceControlList == null) {
                    ArduinoService.getInternalControlsQueue()
                            .put(deviceId, deviceControlList = new LinkedList<String>());
                }
            }
            deviceControlList.add(message.toString());

        } else if (msgContext.equals("OUT")) {

            if (log.isDebugEnabled()) {
                log.debug("Recieved reply from a device: ");
                log.debug("Reply message topic: " + topic);
                log.debug("Reply message: " + message.toString().substring(0, lastIndex));
            }

            synchronized (ArduinoService.getReplyMsgQueue()) {
                replyMessageList = ArduinoService.getReplyMsgQueue().get(deviceId);
                if (replyMessageList == null) {
                    ArduinoService.getReplyMsgQueue()
                            .put(deviceId, replyMessageList = new LinkedList<String>());
                }
            }
            replyMessageList.add(message.toString());
        }
    }

    private void retryMQTTSubscription() {
        Thread retryToSubscribe = new Thread() {
            @Override
            public void run() {
                while (true) {
                    if (!isConnected()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Subscriber re-trying to reach MQTT queue....");
                        }

                        try {
                            ArduinoMQTTSubscriber.super.connectAndSubscribe();
                        } catch (DeviceManagementException e1) {
                            if (log.isDebugEnabled()) {
                                log.debug("Attempt to re-connect to MQTT-Queue failed");
                            }
                        }
                    } else {
                        break;
                    }

                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                        log.error("MQTT: Thread S;eep Interrupt Exception");
                    }
                }
            }
        };

        retryToSubscribe.setDaemon(true);
        retryToSubscribe.start();
    }
}
