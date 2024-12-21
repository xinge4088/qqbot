package org.lonelysail.qqbot.websocket;

import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import org.lonelysail.qqbot.Utils;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class WsSender extends WebSocketClient {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(WsSender.class);
    private String message;
    private final Logger logger;
    private final Utils utils = new Utils();
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    // 使用 ScheduledExecutorService 来定时执行任务
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 需要一个 CompletableFuture 来处理响应的回调
    private CompletableFuture<Boolean> responseFuture;

    public WsSender(JavaPlugin plugin, Configuration config) {
        super(URI.create(Objects.requireNonNull(config.getString("uri"))).resolve("websocket/bot"));
        this.logger = plugin.getLogger();
        HashMap<String, String> headers = new HashMap<>();
        headers.put("name", config.getString("name"));
        headers.put("token", config.getString("token"));
        this.addHeader("info", this.utils.encode(headers));
    }

    public boolean isConnected() {
        return this.isOpen() && !this.isClosed() && !this.isClosing();
    }

    public boolean tryReconnect() {
        // 使用 ScheduledExecutorService 来进行重连
        for (int count = 0; count < 3; count++) {
            logger.warning("[Sender] 检测到与机器人的连接已断开！正在尝试重连……");
            this.reconnect();
            try {
                // 等待重连
                if (this.isConnected()) {
                    this.logger.info("[Sender] 与机器人连接成功！");
                    return true;
                }
                TimeUnit.SECONDS.sleep(1);  // 等待 1 秒后重试
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    public boolean sendData(String event_type, Object data, Boolean waitResponse) {
        // 重连模块
        if (!this.isConnected()) {
            if (!this.tryReconnect()) {
                return false;
            }
        }

        // 通过 CompletableFuture 来异步处理响应
        responseFuture = new CompletableFuture<>();

        HashMap<String, Object> messageData = new HashMap<>();
        messageData.put("data", data);
        messageData.put("type", event_type);
        try {
            this.send(this.utils.encode(messageData));
        } catch (WebsocketNotConnectedException error) {
            logger.warning("[Sender] 发送数据失败！与机器人的连接已断开。");
            return false;
        }

        // 如果不需要等待响应，直接返回
        if (!waitResponse) return true;

        try {
            // 异步等待响应，超时后返回失败
            return responseFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warning("[Sender] 等待响应超时。");
            return false;
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void onMessage(String message) {
        this.lock.lock();
        try {
            this.message = message;
            // 这里使用 responseFuture 来触发返回
            boolean success = (boolean) this.utils.decode(message).get("success");
            responseFuture.complete(success);  // 异步返回结果
            this.condition.signalAll(); // 唤醒等待线程
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("[Sender] WebSocket 连接已建立。");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("[Sender] WebSocket 连接已关闭。关闭原因: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        logger.warning("[Sender] WebSocket 错误: " + ex.getMessage());
    }

    public void sendServerStartup() {
        HashMap<String, Object> data = new HashMap<>();
        if (this.sendData("server_startup", data, true)) {
            this.logger.fine("发送服务器启动消息成功！");
        } else {
            this.logger.warning("发送服务器启动消息失败！请检查机器人是否启动后再次尝试。");
        }
    }

    public void sendServerShutdown() {
        HashMap<String, Object> data = new HashMap<>();
        if (this.sendData("server_shutdown", data, true)) {
            this.logger.fine("发送服务器关闭消息成功！");
        } else {
            this.logger.warning("发送服务器关闭消息失败！请检查机器人是否启动后再次尝试。");
        }
    }

    public void sendPlayerLeft(String name) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("name", name);
        if (this.sendData("player_left", data, true)) {
            this.logger.fine("发送玩家离开消息成功！");
        } else {
            this.logger.warning("发送玩家离开消息失败！");
        }
    }

    // 关闭连接时清理资源
    public void shutdown() {
        this.close();
        scheduler.shutdown();  // 停止定时任务
    }
}
