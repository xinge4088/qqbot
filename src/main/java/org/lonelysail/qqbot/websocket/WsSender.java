package org.lonelysail.qqbot.websocket;

import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import org.lonelysail.qqbot.Utils;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
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
    private ConnectionCallback connectionCallback;

    public WsSender(JavaPlugin plugin, Configuration config) {
        super(URI.create(Objects.requireNonNull(config.getString("uri"))).resolve("websocket/bot"));
        this.logger = plugin.getLogger();
        HashMap<String, String> headers = new HashMap<>();
        headers.put("name", config.getString("name"));
        headers.put("token", config.getString("token"));
        this.addHeader("info", this.utils.encode(headers));
    }

    // 设置连接成功后的回调
    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    // 检查是否已连接
    public boolean isConnected() {
        return this.isOpen() && !this.isClosed() && !this.isClosing();
    }

    // 连接失败时的自动重连机制
    public boolean tryReconnect() {
        for (int count = 0; count < 3; count++) {
            logger.warning("[Sender] 检测到与机器人的连接已断开！正在尝试重连……");
            this.reconnect();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            if (this.isConnected()) {
                this.logger.info("[Sender] 与机器人连接成功！");
                return true;
            }
        }
        return false;
    }

    // WebSocket 连接成功时触发
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("[Sender] 与机器人连接成功！");
        if (connectionCallback != null) {
            connectionCallback.onConnected();
        }
    }

    // WebSocket 收到消息时触发
    @Override
    public void onMessage(String message) {
        this.message = message;
        log.info("[Sender] 收到消息: " + message);
    }

    // WebSocket 关闭时触发
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warning("[Sender] 与机器人连接关闭，原因: " + reason);
        if (connectionCallback != null) {
            connectionCallback.onDisconnected();
        }
    }

    // WebSocket 错误时触发
    @Override
    public void onError(Exception ex) {
        logger.severe("[Sender] 连接错误: " + ex.getMessage());
    }

    // 发送数据到 WebSocket 服务器
    public boolean sendData(String type, Object data, boolean waitResponse) {
        try {
            // 发送数据逻辑，通常需要将数据序列化为JSON或其他格式
            String jsonData = this.utils.serializeToJson(data);
            this.send(jsonData);
            return true;
        } catch (WebsocketNotConnectedException e) {
            logger.warning("[Sender] 连接未建立，无法发送数据！");
            return false;
        }
    }

    // 发送服务器启动信息
    public void sendServerStartup() {
        HashMap<String, Object> data = new HashMap<>();
        if (this.sendData("server_startup", data, true)) this.logger.fine("发送服务器启动消息成功！");
        else this.logger.warning("发送服务器启动消息失败！请检查机器人是否启动后再次尝试。");
    }

    // 发送服务器关闭信息
    public void sendServerShutdown() {
        HashMap<String, Object> data = new HashMap<>();
        if (this.sendData("server_shutdown", data, true)) this.logger.fine("发送服务器关闭消息成功！");
        else this.logger.warning("发送服务器关闭消息失败！请检查机器人是否启动后再次尝试。");
    }

    // 关闭 WebSocket 连接
    public void close() {
        try {
            this.closeBlocking();
        } catch (InterruptedException e) {
            logger.warning("[Sender] 关闭 WebSocket 连接时发生错误: " + e.getMessage());
        }
    }

    // 回调接口，用于在连接和断开时执行特定操作
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
    }
}
