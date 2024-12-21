package org.lonelysail.qqbot.websocket;

import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import org.lonelysail.qqbot.Utils;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class WsSender extends WebSocketClient {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(WsSender.class);
    private String message;
    private final Logger logger;
    private final Utils utils = new Utils();
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    // Constructor with configuration and plugin
    public WsSender(JavaPlugin plugin, Configuration config) {
        super(URI.create(Objects.requireNonNull(config.getString("uri"))).resolve("websocket/bot"));
        this.logger = plugin.getLogger();
        HashMap<String, String> headers = new HashMap<>();
        headers.put("name", config.getString("name"));
        headers.put("token", config.getString("token"));
        this.addHeader("info", this.utils.encode(headers));
    }

    // Check if the WebSocket is connected
    public boolean isConnected() {
        return this.isOpen() && !this.isClosed() && !this.isClosing();
    }

    // Attempt to reconnect up to 3 times if the connection is lost
    public boolean tryReconnect() {
        for (int count = 0; count < 3; count++) {
            logger.warning("[Sender] 检测到与机器人的连接已断开！正在尝试重连……");
            this.reconnect();
            try {
                Thread.sleep(1000);  // Wait 1 second before retrying
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            if (this.isConnected()) {
                this.logger.info("[Sender] 与机器人连接成功！");
                return true;
            }
        }
        logger.warning("[Sender] 重连失败！");
        return false;
    }

    // Send data to the server and optionally wait for a response
    public boolean sendData(String eventType, Object data, boolean waitResponse) {
        if (!this.isConnected() && !this.tryReconnect()) {
            return false;
        }

        HashMap<String, Object> messageData = new HashMap<>();
        messageData.put("data", data);
        messageData.put("type", eventType);

        try {
            this.send(this.utils.encode(messageData));  // Send data as a Base64 encoded JSON string
        } catch (WebsocketNotConnectedException e) {
            logger.warning("[Sender] 发送数据失败！与机器人的连接已断开。");
            return false;
        }

        if (!waitResponse) return true;

        // Wait for response synchronously with a timeout of 5 seconds
        return awaitResponse();
    }

    // Helper method to wait for a response with a timeout
    private boolean awaitResponse() {
        boolean responseReceived = false;
        lock.lock();
        try {
            responseReceived = condition.await(5, TimeUnit.SECONDS);  // Wait for response with a 5-second timeout
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }

        if (!responseReceived) {
            logger.warning("[Sender] 等待响应超时。");
            return false;
        }

        // Return the success status from the response message
        return (boolean) this.utils.decode(this.message).get("success");
    }

    // Send server startup event
    public void sendServerStartup() {
        if (sendData("server_startup", new HashMap<>(), true)) {
            logger.fine("发送服务器启动消息成功！");
        } else {
            logger.warning("发送服务器启动消息失败！");
        }
    }

    // Send server shutdown event
    public void sendServerShutdown() {
        if (sendData("server_shutdown", new HashMap<>(), true)) {
            logger.fine("发送服务器关闭消息成功！");
        } else {
            logger.warning("发送服务器关闭消息失败！");
        }
    }

    // Send player left event
    public void sendPlayerLeft(String name) {
        if (sendData("player_left", name, true)) {
            logger.fine("发送玩家离开消息成功！");
        } else {
            logger.warning("发送玩家离开消息失败！");
        }
    }

    // Send player joined event
    public void sendPlayerJoined(String name) {
        if (sendData("player_joined", name, true)) {
            logger.fine("发送玩家进入消息成功！");
        } else {
            logger.warning("发送玩家进入消息失败！");
        }
    }

    // Send player chat event
    public void sendPlayerChat(String name, String message) {
        List<String> data = Arrays.asList(name, message);
        if (sendData("player_chat", data, false)) {
            logger.fine("发送玩家消息成功！");
        } else {
            logger.warning("发送玩家消息失败！");
        }
    }

    // Send player death event
    public void sendPlayerDeath(String name, String message) {
        List<String> data = Arrays.asList(name, message);
        if (sendData("player_death", data, true)) {
            logger.fine("发送玩家死亡消息成功！");
        } else {
            logger.warning("发送玩家死亡消息失败！");
        }
    }

    // Send a synchronous message (wait for the response)
    public boolean sendSynchronousMessage(String message) {
        return sendData("message", message, true);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        logger.fine("[Sender] 与机器人成功建立链接！");
    }

    @Override
    public void onMessage(String message) {
        // Handle the received message and notify the waiting thread
        lock.lock();
        try {
            this.message = message;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("[Sender] 与机器人的连接已断开！");
    }

    @Override
    public void onError(Exception ex) {
        logger.warning("[Sender] 机器人连接发生 " + ex.getMessage() + " 错误！");
        ex.printStackTrace();
    }
}

