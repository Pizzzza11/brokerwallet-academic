package com.example.brokerfi.swap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.example.brokerfi.token.TokenTxHistoryStore;
import com.example.brokerfi.token.TokenTxRecord;
import com.example.brokerfi.token.TokenWalletHelper;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Continues saving receipts after leaving the screen. Reopening only polls, NEVER resends. */
public final class SwapOperationRunner {
    private static final Set<String> ACTIVE = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public interface Listener { void finished(TokenTxRecord record); }
    private SwapOperationRunner() {}
    public static boolean active(String wallet) { return ACTIVE.contains(wallet.toLowerCase(Locale.ROOT)); }
    public static boolean submit(Context context, BrokerSwapConfig cfg, String wallet, TokenTxRecord record,
                                 String target, String data, BigInteger value, Listener listener) {
        Context app = context.getApplicationContext();
        String lock = wallet.toLowerCase(Locale.ROOT);
        if (!ACTIVE.add(lock)) return false;
        WORK.execute(() -> {
            boolean attempted = false;
            try {
                if (!wallet.equalsIgnoreCase(BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(app)))) throw new IllegalStateException("Account changed");
                for (TokenTxRecord old : TokenTxHistoryStore.getAll(app, wallet))
                    if (TokenTxHistoryStore.isUnresolvedSwap(old)) throw new IllegalStateException("Unresolved operation");
                record.status = "SUBMITTING";
                if (!TokenTxHistoryStore.upsertSwap(app, wallet, record)) throw new IllegalStateException("Cannot save journal");
                String key = TokenWalletHelper.getCurrentPrivateKey(app);
                if (!wallet.equalsIgnoreCase(BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(app)))
                        || !wallet.equalsIgnoreCase(BrokerSwapConfig.walletId(com.example.brokerfi.core.security.SecurityUtil.GetAddress(key))))
                    throw new IllegalStateException("Account changed");
                attempted = true;
                BrokerSwapClient client = new BrokerSwapClient(cfg);
                record.txHash = client.send(key, target, data, value);
                record.status = "PENDING";
                TokenTxHistoryStore.upsertSwap(app, wallet, record);
                poll(app, client, key, wallet, record);
            } catch (Exception e) {
                record.status = record.txHash != null ? "PENDING" : attempted ? "UNKNOWN" : "NOT_SENT";
                TokenTxHistoryStore.upsertSwap(app, wallet, record);
            } finally {
                ACTIVE.remove(lock);
                MAIN.post(() -> listener.finished(record));
            }
        });
        return true;
    }
    public static boolean resume(Context context, BrokerSwapConfig cfg, String wallet, TokenTxRecord record, Listener listener) {
        if (!SwapReceipt.validHash(record.txHash)) return false;
        String lock = wallet.toLowerCase(Locale.ROOT);
        if (!ACTIVE.add(lock)) return false;
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                if (wallet.equalsIgnoreCase(BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(app))))
                    poll(app, new BrokerSwapClient(cfg), TokenWalletHelper.getCurrentPrivateKey(app), wallet, record);
            } catch (Exception ignored) { } finally {
                ACTIVE.remove(lock); MAIN.post(() -> listener.finished(record));
            }
        });
        return true;
    }
    private static void poll(Context app, BrokerSwapClient client, String key, String wallet, TokenTxRecord record) throws Exception {
        for (int i = 0; i < 3; i++) {
            SwapReceipt receipt = client.receipt(key, record.txHash, SwapAsset.valueOf(record.fromAsset),
                    SwapAsset.valueOf(record.toAsset), new BigInteger(record.inputUnits), wallet);
            record.status = receipt.status;
            if (receipt.actualOutput != null && !"APPROVAL".equals(record.type))
                record.actualOutput = SwapQuoteMath.format(receipt.actualOutput, SwapAsset.valueOf(record.toAsset).decimals);
            if (receipt.gasCost != null) record.gasCost = SwapQuoteMath.format(receipt.gasCost, 18);
            TokenTxHistoryStore.upsertSwap(app, wallet, record);
            if (!"PENDING".equals(record.status)) break;
            if (i < 2) Thread.sleep(2500);
        }
    }
}
