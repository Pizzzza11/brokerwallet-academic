package com.example.brokerfi.swap;

import com.example.brokerfi.token.TokenTxRecord;

/** One priority order for the banner and buttons; stale previews never mask an operation. */
final class TradeStatusText {
    static String operation(boolean confirming, boolean preparing, boolean active, TokenTxRecord record) {
        if (preparing) return "发送前检查中，尚未发送";
        if (active) return record != null && SwapReceipt.validHash(record.txHash)
                ? "等待链上确认，请勿重复发送" : "发送中，请勿重复操作";
        if (confirming) return "等待你确认，尚未发送";
        if (record != null) return SwapReceipt.validHash(record.txHash)
                ? "等待链上确认，点击查询原编号（不重发）"
                : "结果未知，点击查看恢复选项（不会自动重发）";
        return "";
    }
    private TradeStatusText() {}
}
