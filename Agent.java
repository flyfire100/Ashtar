package org.example;

import javassist.*;
import org.eclipse.paho.client.mqttv3.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.alibaba.fastjson.JSON;

public class AgentMain {
    private static Map<String, TransformerAdapter> transformerAdaptersMap = new HashMap<>();

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        Properties pro = new Properties();
        try {
            // 将配置文件加载到流中
            InputStream in = AgentMain.class.getResourceAsStream("application.properties");
            pro.load(in);
        }catch(Exception e){
            System.out.println("加载配置文件"+e.toString());
            System.exit(-1);
        }
        String broker =pro.getProperty("broker");
        String topic = pro.getProperty("agenttopic");
        topic = topic + get_local_ip() + "/" + get_local_pid();
        String clientId = "Agent_" + get_local_ip() + "_" + get_local_pid();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(60);
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1);
        try {
            MqttClient client = new MqttClient(broker, clientId);
            client.connect(options);
            client.subscribe(topic, 2);
            System.out.println("Connected to broker.");
            client.setCallback(new MqttCallback() {
                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    System.out.println(payload);
                    if(payload.startsWith("methods")){
                        try {
                            String classname = payload.split(":")[1];
                            Map<String, String> map = new HashMap<>();
                            ClassPool pool = new ClassPool(true);
                            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                                if( !clazz.getName().contains(classname) ){
                                    continue;
                                }
                                CtClass ctClass = pool.get(clazz.getName());
                                for (CtMethod method : ctClass.getDeclaredMethods()) {
                                    map.put(method.getLongName(), clazz.getName());
                                }
                            }
                            String methods = JSON.toJSONString(map);
                            String pub_topic = "AgentMsg/" + get_local_ip() + "/" + get_local_pid();
                            client.publish(pub_topic, methods.getBytes(),2 ,false);
                        }catch ( Exception e ){
                            System.out.println(e.toString());
                        }
                        return;
                    }

                    try {
                        TransformerAdapter transformerAdapter = TransformerAdapter.fromJson(payload);
                        instrumentMethod(transformerAdapter, instrumentation);
                        transformerAdaptersMap.put(transformerAdapter.getClassName(), transformerAdapter);
                    } catch (Exception e) {
                        System.out.println(e.toString());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }

                @Override
                public void connectionLost(Throwable cause) {
                }
            });
        } catch (MqttException e) {
            System.out.println(e.toString());
            throw new RuntimeException(e);
        }
    }

    private static void instrumentMethod(TransformerAdapter methodAdapter, Instrumentation instrumentation) {
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (clazz.getName().equals(methodAdapter.getClassName())) {
                try {
                    TransformerAdapter existingMethodAdapter = transformerAdaptersMap.get(methodAdapter.getMethodName());
                    if (existingMethodAdapter != null) {
                        instrumentation.removeTransformer(existingMethodAdapter);
                    }
                    instrumentation.addTransformer(methodAdapter, true);
                    instrumentation.retransformClasses(clazz);
                    return;
                } catch (UnmodifiableClassException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static String get_local_ip() {
        try {
            InetAddress address = InetAddress.getLocalHost();
            // 获取IP地址
            String ip = address.getHostAddress();
            System.out.println("IP地址：" + ip);
            return ip;
        } catch (Exception e) {
            System.out.println("获取本地IP失败");
            e.printStackTrace();
            return "null";
        }
    }

    private static String get_local_pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int pid = Integer.parseInt(name.split("@")[0]);
        System.out.println("pid is ----------"+String.valueOf(pid));
        return String.valueOf(pid);
    }

    private static class TransformerAdapter implements ClassFileTransformer {

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        private String className;

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        private String methodName;

        public String getCodeBody() {
            return codeBody;
        }

        public void setCodeBody(String codeBody) {
            this.codeBody = codeBody;
        }

        private String codeBody;

        public TransformerAdapter(String methodName, String className, String codeBody) {
            this.methodName = methodName;
            this.className = className;
            this.codeBody = codeBody;
        }

        public String getMethodName() {
            return methodName;
        }

        public static TransformerAdapter fromJson(String json) {
            // Convert JSON to MethodAdapter
            return JSON.parseObject(json, TransformerAdapter.class);
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            ClassPool pool = new ClassPool(true);
            CtClass ctClass = null;
            try {
                System.out.println("enter retransform " + this.getClassName());
                ctClass = pool.get(this.getClassName());
                for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
                    System.out.println("Test class is " + ctMethod.getLongName());
                    if (ctMethod.getLongName().equals(this.getMethodName())) {
                        System.out.println("This is my shellcode");
                        Object[][] paramaters = ctMethod.getParameterAnnotations();
                        for (Object obj : paramaters) {
                            System.out.println(obj);
                        }
                        ctMethod.setBody(this.getCodeBody());
                        ctClass.detach();
                        return ctClass.toBytecode();
                    }
                }
                return null;
            } catch (NotFoundException e) {
                System.out.println(e.toString());
                return null;
            } catch (CannotCompileException e) {
                System.out.println(e.toString());
                return null;
            } catch (IOException e) {
                System.out.println(e.toString());
                return null;
            } catch (ClassNotFoundException e) {
                System.out.println(e.toString());
                return null;
            }
        }
    }
}
