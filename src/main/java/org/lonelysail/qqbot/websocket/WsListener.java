package org.lonelysail.qqbot.websocket;

import com.sun.management.OperatingSystemMXBean;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.lonelysail.qqbot.Utils;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public class WsListener extends WebSocketClient {
    public boolean serverRunning = true;
    private final Logger logger;
    private final Server server;
    private final JavaPlugin plugin;

    private final Utils utils = new Utils();
    private final OperatingSystemMXBean bean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    public WsListener(JavaPlugin plugin, Configuration config) {
        super(URI.create(Objects.requireNonNull(config.getString("uri"))).resolve("websocket/minecraft"));
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.server = plugin.getServer();

        // 添加请求头信息
        HashMap<String, String> headers = new HashMap<>();
        headers.put("name", config.getString("name"));
        headers.put("token", config.getString("token"));
        this.addHeader("type", "Spigot");
        this.addHeader("info", this.utils.encode(headers));
    }

    // 异步执行命令
    private void commandAsync(String data) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            // 异步执行 Minecraft 命令
            server.dispatchCommand(server.getConsoleSender(), data);
        });
    }

    // 获取在线玩家列表
    private void playerListAsync(String data) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            List<String> players = new ArrayList<>();
            for (Player player : server.getOnlinePlayers()) {
                players.add(player.getName());
            }
            // 处理玩家列表 (如果需要可以发送响应)
        });
    }

    // 获取服务器 CPU 和内存占用
    private void serverOccupationAsync(String data) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Runtime runtime = Runtime.getRuntime();
            List<Double> serverOccupations = new ArrayList<>();
            long freeMemory = runtime.freeMemory();
            long totalMemory = runtime.totalMemory();
            serverOccupations.add(bean.getProcessCpuLoad() * 100);
            serverOccupations.add(((double) ((totalMemory - freeMemory)) / totalMemory) * 100);
            // 处理服务器占用 (如果需要可以发送响应)
        });
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        this.logger.info("[Listener] 与机器人成功建立连接！");
    }

    @Override
    public void onMessage(String message) {
        HashMap<String, ?> map = this.utils.decode(message);
        Object data = map.get("data");
        String eventType = (String) map.get("type");
        this.logger.fine("收到消息机器人消息 " + map);

        HashMap<String, Object> responseMessage = new HashMap<>();
        Object response;

        switch (eventType) {
            case "message":
                String broadcastMessage = this.utils.toStringMessage((List) data);
                Bukkit.getScheduler().runTask(this.plugin, () -> server.broadcastMessage(broadcastMessage));
                this.logger.fine("[Listener] 收到广播消息 " + broadcastMessage);
                return;

            case "command":
                // 异步执行命令
                commandAsync((String) data);
                responseMessage.put("success", true);
                responseMessage.put("data", "命令已发送到服务器！");
                break;

            case "player_list":
                // 异步获取玩家列表
                playerListAsync((String) data);
                responseMessage.put("success", true);
                responseMessage.put("data", "玩家列表请求已处理！");
                break;

            case "server_occupation":
                // 异步获取服务器占用信息
                serverOccupationAsync((String) data);
                responseMessage.put("success", true);
                responseMessage.put("data", "服务器占用请求已处理！");
                break;

            default:
                this.logger.warning("[Listener] 未知的事件类型: " + eventType);
                responseMessage.put("success", false);
                responseMessage.put("data", "未知事件类型");
                break;
        }

        // 发送响应消息
        this.send(this.utils.encode(responseMessage));
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        this.logger.warning("[Listener] 与机器人的链接已关闭！");
        if (this.serverRunning) {
            this.logger.info("[Listener] 正在尝试重新链接……");
            Bukkit.getScheduler().runTaskLater(this.plugin, this::reconnect, 100);
        }
    }

    @Override
    public void onError(Exception ex) {
        this.logger.warning("[Listener] 机器人连接发生 " + ex.getMessage() + " 错误！");
        ex.printStackTrace();
    }

}


