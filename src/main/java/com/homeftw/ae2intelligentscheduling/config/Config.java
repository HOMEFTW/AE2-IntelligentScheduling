package com.homeftw.ae2intelligentscheduling.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static final String GENERAL = "general";

    public static int MAX_CPU_PER_NODE = 16;
    public static boolean ENABLE_DEBUG_LOGGING = false;

    private Config() {}

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        MAX_CPU_PER_NODE = configuration.getInt(
            "maxCpuPerNode",
            GENERAL,
            MAX_CPU_PER_NODE,
            1,
            64,
            "Maximum number of CPUs that smart craft may assign to one node.");
        ENABLE_DEBUG_LOGGING = configuration.getBoolean(
            "enableDebugLogging",
            GENERAL,
            ENABLE_DEBUG_LOGGING,
            "Enable verbose logging for smart craft analysis and scheduling.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
