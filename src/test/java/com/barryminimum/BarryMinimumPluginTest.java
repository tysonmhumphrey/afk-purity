package com.barryminimum;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BarryMinimumPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(BarryMinimumPlugin.class);
        RuneLite.main(args);
    }
}
