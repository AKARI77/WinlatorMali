package com.winlator.cmod.ai;

import android.content.Context;

/** Immutable copy of the AI settings, so a request in flight cannot be changed under it. */
public final class AiConfigSnapshot {
    public final String baseUrl;
    public final String apiKey;
    public final String model;
    public final boolean allowCommands;

    public AiConfigSnapshot(String baseUrl, String apiKey, String model, boolean allowCommands) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.allowCommands = allowCommands;
    }

    public static AiConfigSnapshot from(Context context) {
        return new AiConfigSnapshot(
                AiConfig.getBaseUrl(context),
                AiConfig.getApiKey(context),
                AiConfig.getModel(context),
                AiConfig.isCommandAccessAllowed(context)
        );
    }
}
