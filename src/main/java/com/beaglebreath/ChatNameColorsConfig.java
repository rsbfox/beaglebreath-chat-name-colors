package com.beaglebreath;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

import java.awt.*;

@ConfigGroup(ChatNameColorsConfig.GROUP)
public interface ChatNameColorsConfig extends Config
{

	String GROUP = "chatnamecolors";

	String YOUR_NAME_COLOR_KEY = "yournamecolor";

	@ConfigItem(
		keyName = YOUR_NAME_COLOR_KEY,
		name = "Your Name Color",
		description = "The color used to highlight your name"
	)
	default Color yourNameColor()
	{
		return Color.WHITE;
	}

	@ConfigItem(
		keyName = "randomlygenerate",
		name = "Unspecified Users",
		description = "Generate random colors for unspecified users"
	)
	default boolean randomlyGenerate() { return true; }
}
