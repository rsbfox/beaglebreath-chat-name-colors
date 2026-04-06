package com.beaglebreath;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

import java.awt.*;

@ConfigGroup(ChatNameColorsConfig.GROUP)
public interface ChatNameColorsConfig extends Config
{

	String GROUP = "chatnamecolors";

	String RANDOMLY_GENERATE_KEY = "randomlygenerate";
	String COLOR_YOUR_NAME_KEY = "coloryourname";
	String YOUR_NAME_COLOR_KEY = "yournamecolor";
	String COLOR_ENTIRE_MESSAGE_KEY = "colorentiremessage";

	@ConfigItem(
		keyName = RANDOMLY_GENERATE_KEY,
		name = "Unspecified Users",
		description = "Generate random colors for unspecified users",
		position = 0
	)
	default boolean randomlyGenerate() { return true; }

	@ConfigItem(
		keyName = COLOR_YOUR_NAME_KEY,
		name = "Color Your Name",
		description = "Enable a custom color for your username",
		position = 1
	)
	default boolean colorYourName() { return true; }

	@ConfigItem(
		keyName = YOUR_NAME_COLOR_KEY,
		name = "Your Name Color",
		description = "The color used to highlight your name",
		position = 2
	)
	default Color yourNameColor()
	{
		return Color.WHITE;
	}

	@ConfigItem(
		keyName = COLOR_ENTIRE_MESSAGE_KEY,
		name = "Color Entire Message",
		description = "Color the entire chat message instead of only the username",
		position = 3
	)
	default boolean colorEntireMessage() { return false; }
}
