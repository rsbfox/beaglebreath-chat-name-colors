package com.beaglebreath;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;

@Slf4j
@PluginDescriptor(
	name = "Chat Name Colors",
	description = "Differentiate players in your chat with custom colors!",
	tags = {"chat", "name", "color", "message"}
)
public class ChatNameColorsPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "chatNameColors";
	private static final String USER_PREFIX = "USER~";
	private static final String MESSAGE_OPTION = "Message";
	private static final String ADD_FRIEND_OPTION = "Add friend";
	private static final String SET_COLOR_OPTION = "Set Color";

	@Inject
	private Client client;

	@Inject
	private ChatNameColorsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ColorPickerManager colorPickerManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private Gson gson;

	private Map<String, UserColor> userToColorMap;

	@Override
	protected void startUp() throws Exception
	{
		log.info("ChatNameColors started!");
		// Init cache
		userToColorMap = new HashMap<>();
		loadUserColors();
		// Sub to events
		eventBus.register(this);
	}

	@Override
	protected void shutDown() throws Exception
	{
		// Persist cache to config before shutting down
		saveUserColors();
		log.info("ChatNameColors stopped!");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals(ChatNameColorsConfig.GROUP))
		{
			return;
		}

		if (configChanged.getKey().equals(ChatNameColorsConfig.YOUR_NAME_COLOR_KEY)) {
			// Triggering a message will cause a rewrite for chat colors
			// We only need to rewrite if this config key is changed
			clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Chat color config reloaded", null));
		}
	}

	@Provides
	ChatNameColorsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChatNameColorsConfig.class);
	}

	private void writeChatColors()
	{
		// Based on https://github.com/runelite/runelite/blob/a6f1a7794979b016106a23b8a9ca3a18ad6e36d7/runelite-client/src/main/java/net/runelite/client/chat/ChatMessageManager.java#L93
		final Object[] objectStack = client.getObjectStack();
		final int size = client.getObjectStackSize();
		if (size < 3) {
			log.error("Attempted to write chat colors with a small stack: " + size);
			return;
		}
		final String username = (String) objectStack[size - 3];
		if (username == null || username.isEmpty()) {
			// Only coloring user
			return;
		}

		// Replace </col> tags in the message with the new color so embedded </col> won't reset the color
		String sanitizedUsername = sanitizeUsername(username);
		UserColor userColor = getOrCreateUserColor(sanitizedUsername);
		if (userColor == null || userColor.getColor() == null) {
			// Set to default
			return;
		}
		objectStack[size - 3] = ColorUtil.wrapWithColorTag(username, userColor.getColor());
	}

	private UserColor getOrCreateUserColor(String username) {
		boolean isThisPlayer = username.equals(client.getLocalPlayer().getName());
		if (isThisPlayer) {
			if (!config.colorYourName() || config.yourNameColor() == null) {
				return null;
			}
			return new UserColor(
					config.yourNameColor(),
					username,
					new Date()
			);
		}
		UserColor existingColor = userToColorMap.get(username);
		if (existingColor != null) {
			// Update lastSeenAt for caching
			existingColor = existingColor.touch();
			userToColorMap.put(username, existingColor);
			return existingColor;
		}

		if (!config.randomlyGenerate()) {
			return null;
		}

		// Random color
		Random rand = new Random();
		Color userColor = new Color(
				rand.nextFloat(),
				rand.nextFloat(),
				rand.nextFloat()
		);
		UserColor newUserColor = new UserColor(userColor, username, new Date());
		userToColorMap.put(username, newUserColor);
		return newUserColor;
	}

	private String sanitizeUsername(String username) {
		String sanitized = username;
		if (sanitized.contains("<img")) {
			// Get rid of the prefix img in clan chats and what not
			sanitized = sanitized.replaceAll("<img=\\d*>", "");
		}
		if (sanitized.contains("<col")) {
			sanitized = sanitized.replaceAll("<col=[\\w\\d]*>", "");
		}
		if (sanitized.contains("</col>")) {
			sanitized = sanitized.replaceAll("</col>", "");
		}

		return sanitized;
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent)
	{
		final String eventName = scriptCallbackEvent.getEventName();
		if ("chatMessageBuilding".equals(eventName)) {
			writeChatColors();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		final boolean hotKeyPressed = client.isKeyPressed(KeyCode.KC_SHIFT);
		// Check to see if we shift clicked a message
		if (hotKeyPressed &&
			(
				event.getOption().equals(MESSAGE_OPTION) ||
				event.getOption().equals(ADD_FRIEND_OPTION)
			)
		)
		{
			MenuEntry menuEntry = event.getMenuEntry();
			String target = sanitizeUsername(menuEntry.getTarget());

			// Short circuit
			boolean alreadyExists = Arrays.stream(client.getMenuEntries()).anyMatch((me -> me.getOption().equals(SET_COLOR_OPTION)));
			if (alreadyExists) {
				return;
			}
			if (Strings.isNullOrEmpty(target)) {
				log.error("Error detecting player for menu");
				return;
			}
			client.createMenuEntry(-1)
					.setOption(SET_COLOR_OPTION)
					.setTarget(event.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> SwingUtilities.invokeLater(() ->
					{
						Color colorToStart = null;
						if (userToColorMap.containsKey(target)) {
							colorToStart = userToColorMap.get(target).getColor();
						}
						if (colorToStart == null) {
							colorToStart = Color.WHITE;
						}
						RuneliteColorPicker colorPicker = colorPickerManager.create(SwingUtilities.windowForComponent((Panel) client),
								colorToStart, "Set Color for User", false);
						colorPicker.setOnClose(c ->
						{
							setUserColor(target, c);
							// Triggering a message will cause a rewrite for chat colors
							clientThread.invokeLater(this::messageUpdate);
						});
						colorPicker.setVisible(true);
					}));
		}
	}

	private void messageUpdate() {
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Updated user's color", null);
	}

	private void setUserColor(String username, Color color) {
		UserColor userColor = new UserColor(color, username, new Date());
		userToColorMap.put(username, userColor);
		String json = gson.toJson(userColor);
		configManager.setConfiguration(CONFIG_GROUP, USER_PREFIX+username, json);
	}

	private void saveUserColors()
	{
		userToColorMap.keySet().forEach((username) -> {
			UserColor userColor = userToColorMap.get(username);
			String json = gson.toJson(userColor);
			configManager.setConfiguration(CONFIG_GROUP, USER_PREFIX+username, json);
		});
	}

	private void loadUserColors()
	{
		// getConfigurationKeys returns the long form prefix including the config group.
		// So we need to do some parsing
		String KEY_PREFIX = CONFIG_GROUP + "." + USER_PREFIX;
		List<String> keys = configManager.getConfigurationKeys(CONFIG_GROUP);
		for (String key : keys) {
			if (!key.startsWith(KEY_PREFIX)) {
				// Ignore for potential future cases,
				// but we should only have this prefix
				log.error("Unexpected config key: " + key);
				continue;
			}
			String username = key.replace(KEY_PREFIX, "");
			String userKey = USER_PREFIX + username;
			String json = configManager.getConfiguration(CONFIG_GROUP, userKey);
			if (Strings.isNullOrEmpty(json)) {
				log.error("Couldn't find color for key: " + userKey);
				continue;
			}
			UserColor userColor = gson.fromJson(json, UserColor.class);
			userToColorMap.put(username, userColor);
		}
	}

}
