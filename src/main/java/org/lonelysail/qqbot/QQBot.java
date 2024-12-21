package org.lonelysail.qqbot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;
import org.lonelysail.qqbot.server.EventListener;
import org.lonelysail.qqbot.server.commands.QQCommand;
import org.lonelysail.qqbot.websocket.WsListener;
import org.lonelysail.qqbot.websocket.WsSender;

import java.util.Objects;

public final class QQBot extends JavaPlugin {
    public Configuration config;

    private WsListener websocketListener;
    private WsSender websocketSender;

    // 插件加载时调用的方法，初始化配置文件
    @Override
    public void onLoad() {
        this.saveDefaultConfig();
        this.config = this.getConfig();
    }

    // 插件启用时调用的方法，初始化并启动各种服务
    @Override
    public void onEnable() {
        this.getLogger().info("正在初始化与机器人的连接……");

        // 使用异步任务来初始化 WebSocket 连接
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // WebSocket 连接初始化
            websocketSender = new WsSender(this, this.config);
            websocketSender.connect(); // 异步连接 WebSocket
            
            websocketListener = new WsListener(this, this.config);
            websocketListener.connect(); // 异步连接 WebSocket

            // 连接完成后，进行事件监听器和命令执行器注册
            Bukkit.getScheduler().runTask(this, () -> {
                EventListener eventListener = new EventListener(websocketSender);
                QQCommand command = new QQCommand(websocketSender, this.config.getString("name"));
                Objects.requireNonNull(this.getCommand("qq")).setExecutor(command);
                this.getServer().getPluginManager().registerEvents(eventListener, this);

                // 延迟发送服务器启动信息
                Bukkit.getScheduler().runTaskLater(this, () -> websocketSender.sendServerStartup(), 20);
            });
        });
    }

    // 插件禁用时调用的方法，关闭各种服务
    @Override
    public void onDisable() {
        // 在异步线程中执行 WebSocket 的关闭操作，避免阻塞主线程
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // 发送服务器关闭信号
            websocketSender.sendServerShutdown();
            websocketSender.close();
            
            // 停止 WebSocket 监听
            websocketListener.serverRunning = false;
            websocketListener.close();
        });
    }
}
