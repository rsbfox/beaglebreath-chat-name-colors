package com.beaglebreath;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Menu;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
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
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
	name = "Chat Name Colors",
	description = "Differentiate players in your chat with custom colors!",
	tags = {"chat", "name", "color", "message"}
)
public class ChatNameColorsPlugin extends Plugin
{
	private static final String MANUAL_COLORS_KEY = "manualColors";
	private static final String RANDOM_COLORS_KEY = "randomColors";
	private static final String MESSAGE_OPTION = "Message";
	private static final String ADD_FRIEND_OPTION = "Add friend";
	private static final String SET_COLOR_OPTION = "Set Color";
	private static final int MAX_RANDOM_COLORS = 500;

	private final Random random = new Random();

	private boolean loggedIn = false;
	private volatile String pendingMessage = null;

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
		migrateUserColors();
		loadUserColors();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			pendingMessage = "Chat Name Colors started!";
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		// Persist cache to config before shutting down
		saveUserColors();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() -> client.addChatMessage(
				ChatMessageType.GAMEMESSAGE, "", "Chat Name Colors stopped!", null));
		}
		log.info("ChatNameColors stopped!");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN && loggedIn)
		{
			saveUserColors();
			loggedIn = false;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals(ChatNameColorsConfig.GROUP))
		{
			return;
		}

		switch (configChanged.getKey())
		{
			case ChatNameColorsConfig.YOUR_NAME_COLOR_KEY:
			case ChatNameColorsConfig.COLOR_YOUR_NAME_KEY:
			case ChatNameColorsConfig.COLOR_ENTIRE_MESSAGE_KEY:
				pendingMessage = "Chat Name Colors reloaded!";
				break;
			case ChatNameColorsConfig.RANDOMLY_GENERATE_KEY:
				if (!config.randomlyGenerate())
				{
					userToColorMap.entrySet().removeIf(e -> !e.getValue().isManuallySet());
					saveUserColors();
				}
				pendingMessage = "Chat Name Colors reloaded!";
				break;
			case ChatNameColorsConfig.RECOLOR_FRIENDS_KEY:
				rebuildFriendsList();
				break;
		}
	}

	private void rebuildFriendsList()
	{
		clientThread.invokeLater(() ->
		{
			client.runScript(
				ScriptID.FRIENDS_UPDATE,
				InterfaceID.Friends.LIST_CONTAINER,
				InterfaceID.Friends.SORT_NAME,
				InterfaceID.Friends.SORT_RECENT,
				InterfaceID.Friends.SORT_WORLD,
				InterfaceID.Friends.SORT_LEGACY,
				InterfaceID.Friends.LIST,
				InterfaceID.Friends.SCROLLBAR,
				InterfaceID.Friends.LOADING,
				InterfaceID.Friends.TOOLTIP
			);
		});
	}

	@Override
	public void resetConfiguration()
	{
		userToColorMap.clear();
		configManager.unsetConfiguration(ChatNameColorsConfig.GROUP, MANUAL_COLORS_KEY);
		configManager.unsetConfiguration(ChatNameColorsConfig.GROUP, RANDOM_COLORS_KEY);
		super.resetConfiguration();
		pendingMessage = "Chat Name Colors config reset!";
		log.info("ChatNameColors reset!");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (pendingMessage != null)
		{
			// Triggering a message will cause a rewrite for chat colors
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", pendingMessage, null);
			pendingMessage = null;
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
		if (size < 3)
		{
			log.error("Attempted to write chat colors with a small stack: " + size);
			return;
		}
		final String username = (String) objectStack[size - 3];
		if (username == null || username.isEmpty())
		{
			// Only coloring user
			return;
		}

		// Replace </col> tags in the message with the new color so embedded </col> won't reset the color
		UserColor userColor = getOrCreateUserColor(Text.removeTags(username));
		if (userColor == null || userColor.getColor() == null)
		{
			// Set to default
			return;
		}
		objectStack[size - 3] = ColorUtil.wrapWithColorTag(username, userColor.getColor());
		if (config.colorEntireMessage())
		{
			final String message = (String) objectStack[size - 2];
			objectStack[size - 2] = colorMessage(message, userColor.getColor());
		}
	}

	private String colorMessage(String message, Color color)
	{
		if (Strings.isNullOrEmpty(message) || color == null)
		{
			return message;
		}

		// If the message contains </col>, it would end our outer color early.
		// Re-open our desired color after every close tag.
		final String reopen = "<col=" + ColorUtil.toHexColor(color).substring(1) + ">";
		final String patched = message.replace("</col>", "</col>" + reopen);

		return ColorUtil.wrapWithColorTag(patched, color);
	}

	private Color randomColor()
	{
		return new Color(random.nextFloat(), random.nextFloat(), random.nextFloat());
	}

	private UserColor getOrCreateUserColor(String username)
	{
		boolean isThisPlayer = username.equals(client.getLocalPlayer().getName());
		if (isThisPlayer)
		{
			if (!config.colorYourName() || config.yourNameColor() == null)
			{
				return null;
			}
			return new UserColor(config.yourNameColor(), System.currentTimeMillis(), true);
		}
		UserColor existingColor = userToColorMap.get(username);
		if (existingColor != null)
		{
			// Update lastSeenAt for caching
			existingColor = existingColor.touch();
			userToColorMap.put(username, existingColor);
			return existingColor;
		}

		if (!config.randomlyGenerate())
		{
			return null;
		}

		UserColor newUserColor = new UserColor(randomColor(), System.currentTimeMillis(), false);
		userToColorMap.put(username, newUserColor);
		return newUserColor;
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent)
	{
		switch (scriptCallbackEvent.getEventName())
		{
			case "chatMessageBuilding":
				writeChatColors();
				break;
			case "friendsChatSetText":
				if (!config.recolorFriends())
				{
					break;
				}
				Object[] objectStack = client.getObjectStack();
				int objectStackSize = client.getObjectStackSize();
				final String rsn = (String) objectStack[objectStackSize - 1];
				UserColor userColor = userToColorMap.get(Text.removeTags(rsn));
				if (userColor != null)
				{
					objectStack[objectStackSize - 1] = ColorUtil.wrapWithColorTag(rsn, userColor.getColor());
				}
				break;
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		final MenuEntry[] entries = event.getMenuEntries();
		for (int idx = entries.length - 1; idx >= 0; --idx)
		{
			final MenuEntry entry = entries[idx];
			if (!entry.getOption().equals(MESSAGE_OPTION) && !entry.getOption().equals(ADD_FRIEND_OPTION))
			{
				continue;
			}

			final String username = Text.removeTags(entry.getTarget());
			if (Strings.isNullOrEmpty(username))
			{
				continue;
			}

			final boolean isFriendsList = entry.getWidget() != null
				&& WidgetUtil.componentToInterface(entry.getWidget().getId()) == InterfaceID.FRIENDS;

			final UserColor existingColor = userToColorMap.get(username);

			final MenuEntry parent = client.createMenuEntry(idx)
				.setOption(SET_COLOR_OPTION)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE);
			final Menu submenu = parent.createSubMenu();

			// Pick — opens color picker, always sets manual
			submenu.createMenuEntry(0)
				.setOption("Pick")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> SwingUtilities.invokeLater(() ->
				{
					Color colorToStart = existingColor != null ? existingColor.getColor() : Color.WHITE;
					RuneliteColorPicker colorPicker = colorPickerManager.create(
						SwingUtilities.windowForComponent((Panel) client),
						colorToStart, "Set Color for User", false);
					colorPicker.setOnClose(c ->
					{
						setUserColor(username, c);
						pendingMessage = "Color set for " + username;
						if (isFriendsList)
						{
							rebuildFriendsList();
						}
					});
					colorPicker.setVisible(true);
				}));

			// Random — generates new random color
			// If randomlyGenerate is off, treat as manual so it persists
			submenu.createMenuEntry(0)
				.setOption("Random")
				.setType(MenuAction.RUNELITE)
				.onClick(e ->
				{
					boolean manual = existingColor != null ? existingColor.isManuallySet() : !config.randomlyGenerate();
					UserColor newColor = new UserColor(randomColor(), System.currentTimeMillis(), manual);
					userToColorMap.put(username, newColor);
					if (manual)
					{
						saveUserColors();
					}
					pendingMessage = "Color randomized for " + username;
					if (isFriendsList)
					{
						rebuildFriendsList();
					}
				});

			// Conditional third option
			if (existingColor != null)
			{
				if (!existingColor.isManuallySet() && config.randomlyGenerate())
				{
					// Save — promotes random to manual
					submenu.createMenuEntry(0)
						.setOption("Save")
						.setType(MenuAction.RUNELITE)
						.onClick(e ->
						{
							UserColor saved = new UserColor(existingColor.getColor(), System.currentTimeMillis(), true);
							userToColorMap.put(username, saved);
							saveUserColors();
							pendingMessage = "Color saved for " + username;
							if (isFriendsList)
							{
								rebuildFriendsList();
							}
						});
				}
				else if (existingColor.isManuallySet())
				{
					// Reset — demotes manual back to random or removes
					submenu.createMenuEntry(0)
						.setOption("Reset")
						.setType(MenuAction.RUNELITE)
						.onClick(e ->
						{
							if (config.randomlyGenerate())
							{
								UserColor reset = new UserColor(randomColor(), System.currentTimeMillis(), false);
								userToColorMap.put(username, reset);
							}
							else
							{
								userToColorMap.remove(username);
							}
							saveUserColors();
							pendingMessage = "Color reset for " + username;
							if (isFriendsList)
							{
								rebuildFriendsList();
							}
						});
				}
			}
		}
	}

	private void setUserColor(String username, Color color)
	{
		UserColor userColor = new UserColor(color, System.currentTimeMillis(), true);
		userToColorMap.put(username, userColor);
		saveUserColors();
	}

	private void saveUserColors()
	{
		Map<String, UserColor> manualColors = new HashMap<>();
		Map<String, UserColor> randomColors = new HashMap<>();

		userToColorMap.forEach((username, userColor) -> {
			if (userColor.isManuallySet())
			{
				manualColors.put(username, userColor);
			}
			else
			{
				randomColors.put(username, userColor);
			}
		});

		pruneRandomColors(randomColors);

		configManager.setConfiguration(ChatNameColorsConfig.GROUP, MANUAL_COLORS_KEY, gson.toJson(manualColors));
		configManager.setConfiguration(ChatNameColorsConfig.GROUP, RANDOM_COLORS_KEY, gson.toJson(randomColors));
	}

	private void pruneRandomColors(Map<String, UserColor> randomColors)
	{
		if (randomColors.size() <= MAX_RANDOM_COLORS)
		{
			return;
		}

		int toRemove = randomColors.size() - MAX_RANDOM_COLORS;
		log.debug("Pruning {} random color entries", toRemove);

		Comparator<Map.Entry<String, UserColor>> byLastSeen =
			Comparator.comparingLong(e -> e.getValue().getLastSeenAt());

		randomColors.entrySet().stream()
			.sorted(byLastSeen)
			.limit(toRemove)
			.map(Map.Entry::getKey)
			.collect(Collectors.toList())
			.forEach(randomColors::remove);
	}

	private void loadUserColors()
	{
		loadColorMapFromKey(MANUAL_COLORS_KEY, true);
		loadColorMapFromKey(RANDOM_COLORS_KEY, false);
	}

	private void loadColorMapFromKey(String key, boolean manuallySet)
	{
		String json = configManager.getConfiguration(ChatNameColorsConfig.GROUP, key);
		if (Strings.isNullOrEmpty(json))
		{
			return;
		}

		try
		{
			JsonObject obj = gson.fromJson(json, JsonObject.class);
			for (Map.Entry<String, JsonElement> entry : obj.entrySet())
			{
				try
				{
					UserColor userColor = deserializeUserColor(entry.getValue().toString(), manuallySet);
					userToColorMap.put(entry.getKey(), userColor);
				}
				catch (Exception e)
				{
					log.warn("Failed to load color entry for '{}', skipping: {}", entry.getKey(), e.getMessage());
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load color map for key '{}': {}", key, e.getMessage());
		}
	}

	// Migrates legacy config entries
	private void migrateUserColors()
	{
		final String LEGACY_CONFIG_GROUP = "chatNameColors";
		final String LEGACY_USER_PREFIX = "USER~";
		final String legacyKeyPrefix = LEGACY_CONFIG_GROUP + "." + LEGACY_USER_PREFIX;

		List<String> legacyKeys = configManager.getConfigurationKeys(LEGACY_CONFIG_GROUP)
			.stream()
			.filter(k -> k.startsWith(legacyKeyPrefix))
			.map(k -> k.substring((LEGACY_CONFIG_GROUP + ".").length()))
			.collect(Collectors.toList());

		if (legacyKeys.isEmpty())
		{
			return;
		}

		log.info("Migrating {} legacy color entries", legacyKeys.size());
		int migrated = 0, failed = 0;

		for (String key : legacyKeys)
		{
			String json = configManager.getConfiguration(LEGACY_CONFIG_GROUP, key);
			String username = key.replace(LEGACY_USER_PREFIX, "");

			if (Strings.isNullOrEmpty(json))
			{
				continue;
			}

			try
			{
				UserColor userColor = deserializeUserColor(json, true);
				userToColorMap.put(username, userColor);
				configManager.unsetConfiguration(LEGACY_CONFIG_GROUP, key);
				migrated++;
			}
			catch (Exception e)
			{
				log.warn("Could not migrate entry for '{}', skipping: {}", username, e.getMessage());
				failed++;
			}
		}

		saveUserColors();

		log.info("Color migration complete: {} migrated, {} failed", migrated, failed);
	}

	private UserColor deserializeUserColor(String json, boolean manuallySet)
	{
		JsonObject obj = gson.fromJson(json, JsonObject.class);

		Color color = gson.fromJson(obj.get("color"), Color.class);
		long lastSeenAt = parseLastSeenAt(obj);

		return new UserColor(color, lastSeenAt, manuallySet);
	}

	private long parseLastSeenAt(JsonObject json)
	{
		if (!json.has("lastSeenAt"))
		{
			return System.currentTimeMillis();
		}

		JsonElement element = json.get("lastSeenAt");

		// New format: already a long
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber())
		{
			return element.getAsLong();
		}

		// Legacy format: locale-sensitive date string written by Gson's DateTypeAdapter
		String raw = element.getAsString();
		try
		{
			return new SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH).parse(raw).getTime();
		}
		catch (Exception e)
		{
			log.warn("Unrecognised lastSeenAt format '{}', defaulting to now", raw);
			return System.currentTimeMillis();
		}
	}
}