package org.example;

import com.alibaba.fastjson.JSON;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.eclipse.paho.client.mqttv3.*;

import java.io.InputStream;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Main {
    public static void main(String[] args) throws MqttException {
        Properties pro = new Properties();
        try {
            // 将配置文件加载到流中
            InputStream in = Main.class.getResourceAsStream("application.properties");
            pro.load(in);
        }catch(Exception e){
            System.out.println("加载配置文件"+e.toString());
            System.exit(-1);
        }
        String topic = pro.getProperty("loadagenttopic");
        String broker = pro.getProperty("broker");
        topic = topic + get_local_ip();
        String clientId = "LoadAgent_"+get_local_ip();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(60);
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1);

        MqttClient client = new MqttClient(broker, clientId);
        client.connect(options);

        System.out.println("Connected to broker.");

        client.subscribe(topic,2);

        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {
                System.out.println("Connecttion lost");
            }

            public void messageArrived(String topic, MqttMessage message) {
                String content = new String(message.getPayload());
                System.out.println("Message received: " + content);
                if(content.startsWith("pids")){
                    String publish_topic = "LoadAgentMsg/"+get_local_ip();
                    try {
                        client.publish(publish_topic, get_pids().getBytes(), 2, false);
                    }catch (Exception e){
                        e.printStackTrace();
                        System.out.println(e.toString());
                    }
                }

                if(content.startsWith("attach")){
                    String[] msg = content.split(":");
                    String pid = msg[1];
                    try {
                        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
                        try
                        {
                            String path = System.getProperty("user.dir");
                            System.out.println(path);
                            vm.loadAgent(path+"/FireflyAgent-jar-with-dependencies.jar");
                        }catch (Exception e){
                            e.printStackTrace();
                            System.out.println(e.toString());
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                        System.out.println(e.toString());
                    }
                }
            }

            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });
    }

    private static String get_local_ip(){
        try {
            InetAddress address = InetAddress.getLocalHost();
            // 获取IP地址
            String ip = address.getHostAddress();
            System.out.println("IP地址：" + ip);
            return ip;
        } catch (Exception e){
            System.out.println("获取本地IP失败");
            e.printStackTrace();
            return "null";
        }
    }

    private static String get_pids(){
        try {
            List<VirtualMachineDescriptor> vmplist = VirtualMachine.list();
            Map<String, Object> map = new HashMap<>();
            for (VirtualMachineDescriptor vmdesp : vmplist) {
                System.out.println(vmdesp.displayName() + "       " + vmdesp.id());
                map.put(vmdesp.id(), vmdesp.displayName());
            }
            String json = JSON.toJSONString(map);
            return json;
        }catch (Exception e){
            System.out.println(e.toString());
        }
        return null;
    }
}
