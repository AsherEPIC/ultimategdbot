package com.github.alex1304.ultimategdbot.api;

import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.alex1304.ultimategdbot.api.util.PropertyReader;

import discord4j.core.object.presence.Activity;
import discord4j.core.object.presence.Presence;
import discord4j.core.object.util.Snowflake;

public class BotConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("ultimategdbot.config");
	
	private final String token;
	private final String defaultPrefix;
	private final String flagPrefix;
	private final Optional<Snowflake> debugLogChannelId;
	private final Set<Snowflake> emojiGuildIds;
	private final boolean corePluginDisabled;
	private final Presence presence;
	
	public BotConfig(String token, String defaultPrefix, String flagPrefix, Optional<Snowflake> debugLogChannelId,
			Set<Snowflake> emojiGuildIds, boolean corePluginDisabled, Presence presence) {
		this.token = token;
		this.defaultPrefix = defaultPrefix;
		this.flagPrefix = flagPrefix;
		this.debugLogChannelId = debugLogChannelId;
		this.emojiGuildIds = emojiGuildIds;
		this.corePluginDisabled = corePluginDisabled;
		this.presence = presence;
	}

	public static BotConfig fromProperties(Properties properties) {
		var propertyReader = new PropertyReader(properties);
		var activity = propertyReader.read("presence_activity", false)
				.map(value -> {
					if (value.isEmpty() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")) {
						return null;
					} else if (value.matches("playing:.+")) {
						return Activity.playing(value.split(":")[1]);
					} else if (value.matches("watching:.+")) {
						return Activity.watching(value.split(":")[1]);
					} else if (value.matches("listening:.+")) {
						return Activity.listening(value.split(":")[1]);
					} else if (value.matches("streaming:[^:]+:[^:]+")) {
						var split = value.split(":");
						return Activity.streaming(split[1], split[2]);
					}
					LOGGER.warn("presence_activity: Expected one of: ''|'none'|'null', 'playing:<text>', 'watching:<text>', 'listening:<text>'"
							+ " or 'streaming:<url>' in lower case. Defaulting to no activity");
					return null;
				})
				.orElse(null);
		return new BotConfig(propertyReader.read("token", true).orElseThrow(),
				propertyReader.read("default_prefix", true).orElseThrow(),
				propertyReader.read("flag_prefix", true).orElseThrow(),
				propertyReader.read("debug_log_channel_id", false).map(Snowflake::of),
				propertyReader.readAsStream("emoji_guild_ids", ",").map(Snowflake::of).collect(toUnmodifiableSet()),
				propertyReader.read("disable_core_plugin", false).map(Boolean::parseBoolean).orElse(false),
				propertyReader.read("presence_status", false).map(value -> {
					switch (value) {
						case "online": return Presence.online(activity);
						case "idle": return Presence.idle(activity);
						case "dnd": return Presence.doNotDisturb(activity);
						case "invisible": return Presence.invisible();
						default:
							LOGGER.warn("presence_status: Expected one of 'online', 'idle', 'dnd', 'invisible'. Defaulting to 'online'.");
							return Presence.online(activity);
					}
				}).orElse(Presence.online(activity)));
	}

	public String getToken() {
		return token;
	}

	public String getDefaultPrefix() {
		return defaultPrefix;
	}

	public String getFlagPrefix() {
		return flagPrefix;
	}

	public Optional<Snowflake> getDebugLogChannelId() {
		return debugLogChannelId;
	}

	public Set<Snowflake> getEmojiGuildIds() {
		return emojiGuildIds;
	}

	public boolean isCorePluginDisabled() {
		return corePluginDisabled;
	}

	public Presence getPresence() {
		return presence;
	}

	@Override
	public int hashCode() {
		return Objects.hash(corePluginDisabled, debugLogChannelId, defaultPrefix, emojiGuildIds, flagPrefix,
				presence, token);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof BotConfig))
			return false;
		BotConfig other = (BotConfig) obj;
		return corePluginDisabled == other.corePluginDisabled
				&& Objects.equals(debugLogChannelId, other.debugLogChannelId)
				&& Objects.equals(defaultPrefix, other.defaultPrefix)
				&& Objects.equals(emojiGuildIds, other.emojiGuildIds)
				&& Objects.equals(flagPrefix, other.flagPrefix)
				&& Objects.equals(presence, other.presence)
				&& Objects.equals(token, other.token);
	}

	@Override
	public String toString() {
		return "BotConfig{token=<masked>, defaultPrefix=" + defaultPrefix + ", flagPrefix=" + flagPrefix
				+ ", debugLogChannelId=" + debugLogChannelId + ", emojiGuildIds=" + emojiGuildIds
				+ ", corePluginDisabled=" + corePluginDisabled + ", presence=" + presence
				+ "}";
	}

	
}