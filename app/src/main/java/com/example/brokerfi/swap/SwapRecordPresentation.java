package com.example.brokerfi.swap;

import com.example.brokerfi.token.TokenTxRecord;

/** Estimated amounts and chain-confirmed amounts are deliberately separate. */
public final class SwapRecordPresentation {
    private SwapRecordPresentation() {}
    public static String status(String state) {
        switch (state) {
            case "SUBMITTING": return "发送中／待核查";
            case "UNKNOWN": return "发送结果未知，请勿重复";
            case "PENDING": return "等待链上确认";
            case "SUCCESS": return "链上成功";
            case "FAILED": return "链上失败";
            case "NOT_SENT": return "未发送";
            default: return "已人工核查";
        }
    }
    public static String details(TokenTxRecord r) {
        boolean approval = "APPROVAL".equals(r.type);
        return status(r.status) + "\n" + (approval ? "授权限额：" : "支付：") + r.amountDisplay + " " + r.fromSymbol
                + (approval ? "\n授权对象：" + r.routerAddress : "\n预计到账：" + r.estimatedOutput + " " + r.toSymbol
                + "\n最低到账：" + r.minimumOutput + " " + r.toSymbol
                + "\n实际到账：" + (r.actualOutput == null ? "待核实（不使用预计值代替）" : r.actualOutput + " " + r.toSymbol)
                + "\n滑点：" + r.slippageBps / 100.0 + "%　价格影响：" + r.impactBps / 100.0 + "%")
                + "\n网络：" + r.chainId + "\nRouter：" + r.routerAddress
                + "\n支付资产合约：" + (r.contractAddress == null || r.contractAddress.isEmpty() ? "原生 BKC" : r.contractAddress)
                + (approval ? "" : "\n到账资产合约：" + (r.toContractAddress == null || r.toContractAddress.isEmpty() ? "原生 BKC" : r.toContractAddress))
                + "\n手续费：" + (r.gasCost == null ? "待核实" : r.gasCost + " BKC")
                + "\n交易编号：" + (r.txHash == null ? "尚未取得，请先核查链上结果" : r.txHash);
    }
}
