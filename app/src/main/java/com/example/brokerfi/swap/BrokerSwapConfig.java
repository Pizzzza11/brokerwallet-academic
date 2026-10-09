package com.example.brokerfi.swap;

import android.content.Context;
import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Verified fixed-pair deployment; does not change the wallet's global chain configuration. */
public final class BrokerSwapConfig {
    private long chainId;
    private String factory, router, pair, wbkc, musdt, routerCodeHash;

    public static BrokerSwapConfig load(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("broker_swap_deployment.json")) {
            return parse(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    BrokerSwapConfig.class));
        }
    }

    public static BrokerSwapConfig fromJson(String json) {
        return parse(new Gson().fromJson(json, BrokerSwapConfig.class));
    }

    private static BrokerSwapConfig parse(BrokerSwapConfig config) {
        if (config == null || config.chainId <= 0) throw new IllegalArgumentException("Invalid swap network");
        config.factory = address(config.factory);
        config.router = address(config.router);
        config.pair = address(config.pair);
        config.wbkc = address(config.wbkc);
        config.musdt = address(config.musdt);
        if (config.wbkc.equals(config.musdt)) throw new IllegalArgumentException("Identical swap assets");
        if (config.routerCodeHash == null || !config.routerCodeHash.matches("0x[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Missing router code fingerprint");
        config.routerCodeHash = config.routerCodeHash.toLowerCase(Locale.US);
        return config;
    }

    public static String address(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{40}")
                || value.matches("0x0{40}")) throw new IllegalArgumentException("Invalid contract address");
        return value.toLowerCase(Locale.US);
    }

    public long chainId() { return chainId; }
    public String factory() { return factory; }
    public String router() { return router; }
    public String pair() { return pair; }
    public String wbkc() { return wbkc; }
    public String musdt() { return musdt; }
    public String routerCodeHash() { return routerCodeHash; }

    public String tokenAddress(SwapAsset asset, String originalWrapped) {
        switch (asset) {
            case ORIGINAL_WBKC: return address(originalWrapped);
            case SWAP_WBKC: return wbkc;
            case MUSDT: return musdt;
            default: return "";
        }
    }
}
