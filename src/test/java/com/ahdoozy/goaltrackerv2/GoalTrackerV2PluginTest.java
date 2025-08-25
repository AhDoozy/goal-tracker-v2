package com.ahdoozy.goaltrackerv2;

import com.ahdoozy.goaltrackerv2.GoalTrackerV2Plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GoalTrackerV2PluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GoalTrackerV2Plugin.class);
		RuneLite.main(args);
	}
}