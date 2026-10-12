package com.example.brokerfi.swap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One queue for Trade preview/preflight batches. Never queues or retries transaction sends. */
final class TradeReadQueue {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "trade-chain-reads");
        thread.setDaemon(true);
        return thread;
    });
    private TradeReadQueue() {}
    static void execute(Runnable work) { WORK.execute(work); }
}
