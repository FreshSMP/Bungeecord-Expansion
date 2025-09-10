package com.extendedclip.papi.bungeeexpansion;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Taskable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class BungeeExpansion extends PlaceholderExpansion
        implements PluginMessageListener, Taskable, Configurable, Listener {

    private static final String MESSAGE_CHANNEL = "BungeeCord";
    private static final String SERVERS_CHANNEL = "GetServers";
    private static final String PLAYERS_CHANNEL = "PlayerCount";
    private static final String CONFIG_INTERVAL = "check_interval";

    private static final Splitter SPLITTER = Splitter.on(",").trimResults();

    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> unchangedCounts = new ConcurrentHashMap<>();

    @Override
    public @NotNull String getIdentifier() {
        return "bungee";
    }

    @Override
    public @NotNull String getAuthor() {
        return "clip";
    }

    @Override
    public @NotNull String getVersion() {
        return "2.3";
    }

    @Override
    public Map<String, Object> getDefaults() {
        return Collections.singletonMap(CONFIG_INTERVAL, 30);
    }

    @Override
    public String onRequest(@NotNull OfflinePlayer player, @NotNull String identifier) {
        final int value;

        switch (identifier.toLowerCase()) {
            case "all", "total" -> value = counts.values().stream().mapToInt(Integer::intValue).sum();
            default -> value = counts.getOrDefault(identifier.toLowerCase(), 0);
        }

        return String.valueOf(value);
    }

    @Override
    public void start() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, getPlaceholderAPI());
    }

    @Override
    public void stop() {
        counts.clear();
        unchangedCounts.clear();
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        if (counts.isEmpty()) {
            sendServersChannelMessage();
        } else {
            for (String server : counts.keySet()) {
                sendPlayersChannelMessage(server);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        if (!counts.isEmpty()) {
            for (String server : counts.keySet()) {
                sendPlayersChannelMessage(server);
            }
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!MESSAGE_CHANNEL.equals(channel)) {
            return;
        }

        final ByteArrayDataInput in = ByteStreams.newDataInput(message);
        try {
            switch (in.readUTF()) {
                case PLAYERS_CHANNEL -> {
                    final String server = in.readUTF();
                    try {
                        final int newCount = in.readInt();
                        final int oldCount = counts.getOrDefault(server.toLowerCase(), -1);

                        if (oldCount == newCount) {
                            unchangedCounts
                                    .computeIfAbsent(server.toLowerCase(), k -> new AtomicInteger(0))
                                    .incrementAndGet();
                        } else {
                            unchangedCounts.put(server.toLowerCase(), new AtomicInteger(0));
                        }

                        counts.put(server.toLowerCase(), newCount);
                    } catch (Exception ignored) {
                    }
                }
                case SERVERS_CHANNEL -> {
                    final String serversString = in.readUTF();
                    SPLITTER.split(serversString)
                            .forEach(name -> counts.putIfAbsent(name.toLowerCase(), 0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void sendServersChannelMessage() {
        sendMessage(SERVERS_CHANNEL, out -> { });
    }

    private void sendPlayersChannelMessage(@NotNull String serverName) {
        sendMessage(PLAYERS_CHANNEL, out -> out.writeUTF(serverName));
    }

    private void sendMessage(@NotNull String subChannel, @NotNull Consumer<ByteArrayDataOutput> consumer) {
        final Player player = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        if (player == null) {
            return;
        }

        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(subChannel);
        consumer.accept(out);

        player.sendPluginMessage(getPlaceholderAPI(), MESSAGE_CHANNEL, out.toByteArray());
    }
}
