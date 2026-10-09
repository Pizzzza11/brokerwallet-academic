package com.example.brokerfi.swap;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.example.brokerfi.R;
import com.example.brokerfi.token.TokenItem;
import com.example.brokerfi.token.TokenStore;
import com.example.brokerfi.token.TokenSwapActivity;
import com.example.brokerfi.token.TokenTopBarHelper;
import com.example.brokerfi.token.TokenTxDetailDialog;
import com.example.brokerfi.token.TokenTxHistoryStore;
import com.example.brokerfi.token.TokenTxRecord;
import com.example.brokerfi.token.TokenWalletHelper;
import com.example.brokerfi.token.wrappedbkc.wrappedBkcContractHelper;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wallet-side MiniV2 integration. Native↔old wBKC remains available without a Swap server. */
public class BrokerSwapActivity extends AppCompatActivity {
    private static final ExecutorService READS = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private BrokerSwapConfig config;
    private BrokerSwapClient client;
    private String original, wallet = "", error = "";
    private SwapAsset from = SwapAsset.BKC, to = SwapAsset.MUSDT;
    private SwapPoolSnapshot pool;
    private BigInteger nativeBalance, payBalance, receiveBalance, allowance, gasReserve;
    private long readAt, validatedAt;
    private boolean loading, confirming, preparing, resumed;
    private int generation, slippage = 50;
    private EditText amount;
    private TextView receive, status, details, paySymbol, receiveSymbol, payBalanceView, receiveBalanceView;
    private Button submit, slipButton;
    private SwipeRefreshLayout refresh;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            render();
            if (!loading && !confirming && !preparing && error.isEmpty() && !SwapOperationRunner.active(wallet)) fetch();
            main.postDelayed(this, 15000);
        }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_swap);
        TokenTopBarHelper.bind(this);
        ((TextView)findViewById(R.id.token_page_title)).setText(R.string.broker_swap_title);
        amount = findViewById(R.id.token_pay_amount); receive = findViewById(R.id.token_receive_amount);
        amount.setSingleLine(true);
        status = findViewById(R.id.broker_swap_status); details = findViewById(R.id.broker_swap_details);
        paySymbol = findViewById(R.id.token_pay_symbol); receiveSymbol = findViewById(R.id.token_receive_symbol);
        payBalanceView = findViewById(R.id.token_pay_balance); receiveBalanceView = findViewById(R.id.token_receive_balance);
        int selectorWidth = (int)(180 * getResources().getDisplayMetrics().density);
        payBalanceView.setMaxWidth(selectorWidth); receiveBalanceView.setMaxWidth(selectorWidth);
        paySymbol.setTextSize(14); receiveSymbol.setTextSize(14);
        submit = findViewById(R.id.token_confirm_button); slipButton = findViewById(R.id.broker_swap_slippage);
        refresh = findViewById(R.id.token_refresh); findViewById(R.id.broker_swap_panel).setVisibility(View.VISIBLE);
        try {
            config = BrokerSwapConfig.load(this); client = new BrokerSwapClient(config);
            original = wrappedBkcContractHelper.resolveContractAddress(this);
            String preselect = getIntent().getStringExtra(TokenSwapActivity.EXTRA_PAY_CONTRACT);
            if (preselect != null) for (SwapAsset asset : SwapAsset.values())
                if (!contract(asset).isEmpty() && contract(asset).equalsIgnoreCase(preselect)) { from = asset; break; }
            if (getIntent().getBooleanExtra(TokenSwapActivity.EXTRA_UNWRAP, false) && from == SwapAsset.BKC)
                from = SwapAsset.ORIGINAL_WBKC;
            if (from == SwapAsset.ORIGINAL_WBKC || getIntent().getBooleanExtra(TokenSwapActivity.EXTRA_UNWRAP, false))
                to = SwapAsset.BKC;
            else if (from == SwapAsset.MUSDT) to = SwapAsset.BKC;
        } catch (Exception e) { error = "部署配置不可用，已禁用交易"; }
        findViewById(R.id.token_pay_token_selector).setOnClickListener(v -> select(true));
        findViewById(R.id.token_receive_token_selector).setOnClickListener(v -> select(false));
        findViewById(R.id.token_swap_direction).setOnClickListener(v -> {
            if (locked()) return;
            SwapAsset old = from; from = to; to = old; changed();
        });
        findViewById(R.id.token_pay_max).setOnClickListener(v -> {
            if (locked() || !fresh() || payBalance == null || gasReserve == null) return;
            BigInteger max = from == SwapAsset.BKC ? payBalance.subtract(requiredGas()).max(BigInteger.ZERO) : payBalance;
            amount.setText(SwapQuoteMath.format(max, from.decimals));
        });
        slipButton.setOnClickListener(v -> {
            if (locked()) return;
            new AlertDialog.Builder(this).setTitle("允许的到账浮动（滑点）")
                    .setItems(new String[]{"0.5%（默认）", "1%", "2%", "5%（较高风险）"}, (d, which) -> {
                        slippage = new int[]{50,100,200,500}[which]; render();
                    }).show();
        });
        findViewById(R.id.broker_swap_history).setOnClickListener(v -> history());
        refresh.setOnRefreshListener(this::fetch);
        submit.setOnClickListener(v -> confirm());
        amount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable s) { }
        });
        render();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        String current = BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(this));
        if (!current.equalsIgnoreCase(wallet)) {
            wallet = current; generation++; validatedAt = 0; clearRead();
        }
        fetch(); resumePending(); main.removeCallbacks(tick); main.postDelayed(tick, 15000);
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacks(tick); super.onDestroy(); }
    private String contract(SwapAsset asset) { return config.tokenAddress(asset, original); }
    private boolean amm() { return SwapAsset.isAmm(from, to); }
    private boolean supported() { return amm() || SwapAsset.isWrap(from, to) || SwapAsset.isUnwrap(from, to); }
    private boolean locked() { return confirming || preparing || SwapOperationRunner.active(wallet); }
    private boolean sameAccount(String address) { return address.equalsIgnoreCase(BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(this))); }
    private boolean fresh() { return readAt > 0 && SystemClock.elapsedRealtime() - readAt <= SwapPoolSnapshot.MAX_AGE_MS; }
    private void clearRead() { pool = null; readAt = 0; nativeBalance = payBalance = receiveBalance = allowance = gasReserve = null; }
    private void changed() { generation++; clearRead(); amount.setText(""); render(); fetch(); }
    private void select(boolean paying) {
        if (locked() || config == null) return;
        List<SwapAsset> options = new ArrayList<>();
        for (SwapAsset asset : SwapAsset.values()) {
            SwapAsset other = paying ? to : from;
            if (paying ? SwapAsset.isAmm(asset, other) || SwapAsset.isWrap(asset, other) || SwapAsset.isUnwrap(asset, other)
                    : SwapAsset.isAmm(other, asset) || SwapAsset.isWrap(other, asset) || SwapAsset.isUnwrap(other, asset)) options.add(asset);
        }
        // A pay-side selection can also move to a supported default receive asset.
        if (paying) { options.clear(); java.util.Collections.addAll(options, SwapAsset.values()); }
        String[] labels = new String[options.size()];
        for (int i=0;i<labels.length;i++) labels[i] = options.get(i).symbol + (options.get(i)==SwapAsset.MUSDT ? "（测试资产）" : "");
        new AlertDialog.Builder(this).setTitle(paying ? "支付资产" : "接收资产").setItems(labels, (d, which) -> {
            if (locked()) return;
            if (paying) {
                from = options.get(which);
                if (!supported()) to = from == SwapAsset.ORIGINAL_WBKC || from == SwapAsset.MUSDT ? SwapAsset.BKC : SwapAsset.MUSDT;
            } else to = options.get(which);
            changed();
        }).show();
    }
    private void fetch() {
        if (config == null || wallet.isEmpty() || preparing || confirming) { refresh.setRefreshing(false); render(); return; }
        if (loading) return;
        loading = true; error = ""; render();
        final int id = generation; final String address = wallet;
        final SwapAsset pay = from, out = to;
        final boolean needsProtocol = pay == SwapAsset.SWAP_WBKC || out == SwapAsset.SWAP_WBKC || pay == SwapAsset.MUSDT || out == SwapAsset.MUSDT;
        final boolean validate = needsProtocol && (validatedAt == 0 || SystemClock.elapsedRealtime()-validatedAt > 600000);
        READS.execute(() -> {
            String stage = "账户";
            try {
                String key = TokenWalletHelper.getCurrentPrivateKey(getApplicationContext());
                if (!sameAccount(address)) throw new IllegalStateException("账户已切换");
                if (validate) {
                    stage = "合约校验";
                    client.validate(key);
                    final long checkedAt = SystemClock.elapsedRealtime();
                    main.post(() -> {
                        // Metadata/code validation succeeded independently of later volatile reads.
                        // A failed reserve/balance read must not restart all eight immutable checks.
                        if (!isDestroyed() && id == generation && sameAccount(address)) {
                            validatedAt = checkedAt;
                            importProtocolTokens();
                        }
                    });
                }
                stage = "BKC 余额";
                BigInteger nativeAmount = client.balance(key, address, "");
                stage = "支付资产余额";
                BigInteger payAmount = pay == SwapAsset.BKC ? nativeAmount : client.balance(key, address, contract(pay));
                stage = "到账资产余额";
                BigInteger receiveAmount = out == SwapAsset.BKC ? nativeAmount : client.balance(key, address, contract(out));
                stage = "手续费";
                BigInteger gas = client.gasReserve();
                stage = "授权额度";
                BigInteger approved = SwapAsset.isAmm(pay, out) && pay != SwapAsset.BKC
                        ? client.allowance(key, address, contract(pay)) : BigInteger.ZERO;
                // Pool calls on Dash may wait for execution. Capture the quote LAST so balance/allowance
                // reads cannot consume its 45-second validity before it reaches the screen.
                stage = "资金池";
                SwapPoolSnapshot snapshot = SwapAsset.isAmm(pay, out) ? client.pool(key) : null;
                main.post(() -> {
                    loading = false; refresh.setRefreshing(false);
                    if (isDestroyed()) return;
                    if (id != generation || !sameAccount(address)) { fetch(); return; }
                    pool = snapshot; nativeBalance = nativeAmount; payBalance = payAmount; receiveBalance = receiveAmount;
                    gasReserve = gas; allowance = approved; readAt = SystemClock.elapsedRealtime(); error = "";
                    render();
                });
            } catch (Exception e) {
                final String failedStage = stage;
                main.post(() -> {
                    loading = false; refresh.setRefreshing(false);
                    if (isDestroyed()) return;
                    if (id != generation) { fetch(); return; }
                    clearRead(); error = failedStage + "读取失败，已停止自动重试。下拉刷新后再试；不会使用旧报价发交易。"; render();
                });
            }
        });
    }
    private void importProtocolTokens() {
        for (SwapAsset asset : new SwapAsset[]{SwapAsset.SWAP_WBKC, SwapAsset.MUSDT}) {
            if (TokenStore.contains(this, contract(asset))) continue;
            TokenItem item = new TokenItem(); item.setContractAddress(contract(asset)); item.setSymbol(asset.symbol);
            item.setName(asset==SwapAsset.MUSDT ? "BrokerSwap mock USDT (test only)" : "BrokerSwap wrapped BKC");
            item.setDecimals(asset.decimals); item.setEnabled(true); item.setBuiltIn(false); TokenStore.addCustom(this,item);
        }
    }
    private boolean needsApproval(BigInteger input) { return amm() && from != SwapAsset.BKC && (allowance == null || allowance.compareTo(input)<0); }
    private BigInteger requiredGas() {
        if (gasReserve == null) return BigInteger.ZERO;
        return amm() && from != SwapAsset.BKC ? gasReserve.multiply(BigInteger.valueOf(2)) : gasReserve;
    }
    private TokenTxRecord unresolved() {
        for (TokenTxRecord r : TokenTxHistoryStore.getAll(this, wallet)) if (TokenTxHistoryStore.isUnresolvedSwap(r)) return r;
        return null;
    }
    private SwapQuoteMath.Quote quote(BigInteger input) { return SwapQuoteMath.quote(pool, from, to, input, slippage, SystemClock.elapsedRealtime()); }
    private String validation(BigInteger input, SwapQuoteMath.Quote quote) {
        if (!sameAccount(wallet)) return "账户已切换，请重新进入";
        if (!fresh() || (amm() && (pool==null || !pool.isFresh(SystemClock.elapsedRealtime())))) return "报价已过期，请下拉刷新";
        if (!supported()) return "这两种资产不能直接兑换";
        if (payBalance==null || payBalance.compareTo(input)<0) return "支付资产余额不足";
        if (nativeBalance==null || nativeBalance.compareTo(requiredGas().add(from==SwapAsset.BKC ? input : BigInteger.ZERO))<0)
            return "BKC 不足以支付金额和预留手续费";
        if (quote!=null && quote.blocked()) return "价格影响达到 10%，已禁止交易";
        if (unresolved()!=null) return "还有未确认交易，请先查询兑换记录";
        return "";
    }
    private void render() {
        if (submit == null) return;
        paySymbol.setText(from.symbol); receiveSymbol.setText(to.symbol);
        ((TextView)findViewById(R.id.token_pay_token_icon)).setText(from==SwapAsset.MUSDT ? "m" : "B");
        ((TextView)findViewById(R.id.token_receive_token_icon)).setText(to==SwapAsset.MUSDT ? "m" : "B");
        payBalanceView.setText("余额：" + (payBalance==null ? "读取中 / 未验证" : SwapQuoteMath.format(payBalance,from.decimals)));
        receiveBalanceView.setText("余额：" + (receiveBalance==null ? "读取中 / 未验证" : SwapQuoteMath.format(receiveBalance,to.decimals)));
        slipButton.setText("滑点：" + slippage/100.0 + "%（点击调整）"); slipButton.setEnabled(!locked() && amm());
        amount.setEnabled(!locked());
        status.setText(getString(R.string.broker_swap_test_notice) + "\n" + (wallet.isEmpty() ? "请先登录钱包" : preparing ? "发送前重新检查…"
                : SwapOperationRunner.active(wallet) ? "交易处理中，请勿重复操作" : loading ? "正在读取链上数据…" : error.isEmpty() ? "网络 1051 · 已验证资产 / 金额精度" : error));
        receive.setText("—"); details.setText(""); submit.setEnabled(false);
        if (config == null || wallet.isEmpty()) { submit.setText("钱包或部署未就绪"); return; }
        try {
            BigInteger input = SwapQuoteMath.parseUnits(amount.getText().toString(), from.decimals);
            SwapQuoteMath.Quote q = amm() ? quote(input) : null;
            BigInteger output = q==null ? input : q.output;
            receive.setText(SwapQuoteMath.format(output,to.decimals));
            details.setText(q==null ? "包装 / 解包：1 : 1，无资金池兑换费" : quoteDetails(q));
            String problem = validation(input,q);
            submit.setText(!problem.isEmpty() ? problem : needsApproval(input) ? "先授权本次金额（不会立即兑换）" : "确认兑换");
            // A background refresh must not disable a still-valid snapshot. validation() and the
            // final preflight enforce freshness, current balances, current allowance and minimum out.
            submit.setEnabled(problem.isEmpty() && !locked());
        } catch (Exception e) { submit.setText(preparing ? "发送前重新检查…" : SwapOperationRunner.active(wallet) ? "交易处理中，请勿重复操作"
                : amount.getText().length()==0 ? "请输入金额"
                : loading && pool==null ? "正在读取链上报价" : "金额精度不符、报价过期或金额太小"); }
    }
    private String quoteDetails(SwapQuoteMath.Quote q) {
        return "预计到账：" + SwapQuoteMath.format(q.output,q.to.decimals) + " " + q.to.symbol
                + "\n最低到账：" + SwapQuoteMath.format(q.minimum,q.to.decimals) + " " + q.to.symbol
                + "\n兑换费：0.3%（报价已包含）　价格影响：" + q.impactBps/100.0 + "%"
                + (q.impactBps>=200 ? "\n警告：交易量相对资金池较大" : "")
                + "\n手续费预留：" + SwapQuoteMath.format(requiredGas(),18) + " BKC（估算，非保证）";
    }
    private void confirm() {
        if (locked()) return;
        try {
            BigInteger input = SwapQuoteMath.parseUnits(amount.getText().toString(),from.decimals);
            SwapQuoteMath.Quote q = amm() ? quote(input) : null;
            String problem = validation(input,q); if (!problem.isEmpty()) { toast(problem); return; }
            boolean approval = needsApproval(input);
            final int id = generation; final String address = wallet; final SwapAsset pay = from, out = to;
            confirming = true; render();
            String message = "支付：" + SwapQuoteMath.format(input,pay.decimals) + " " + pay.symbol + "\n"
                    + (approval ? "只授权本次金额给已验证 Router。授权成功后需要再次点击兑换。" : q==null ? "包装 / 解包到账 1 : 1" : quoteDetails(q))
                    + "\n\n账户：" + address + "\n网络：1051\n目标合约：" + (q==null ? contract(pay.isWrapped()?pay:out) : config.router());
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(approval ? "确认限额授权" : "确认兑换")
                    .setMessage(message).setNegativeButton("取消",null).setPositiveButton("继续", (d,w) -> {
                        if (!approval && q!=null && q.impactBps>=500) {
                            main.post(() -> {
                            confirming = true; render();
                            AlertDialog warning = new AlertDialog.Builder(this).setTitle("价格影响超过 5%")
                                    .setMessage("此交易会明显改变池内价格，可能亏损。仍要继续吗？")
                                    .setNegativeButton("取消",null).setPositiveButton("仍然继续", (d2,w2) -> prepare(id,address,pay,out,input,q,approval)).create();
                            warning.setOnDismissListener(x -> { confirming=false; render(); }); warning.show();
                            });
                        } else prepare(id,address,pay,out,input,q,approval);
                    }).create();
            dialog.setOnDismissListener(d -> { confirming=false; render(); }); dialog.show();
        } catch (Exception e) { toast("请先刷新报价并检查金额"); }
    }
    private void prepare(int id, String address, SwapAsset pay, SwapAsset out, BigInteger input, SwapQuoteMath.Quote confirmed, boolean approval) {
        preparing = true; render();
        READS.execute(() -> {
            try {
                if (id!=generation || !sameAccount(address)) throw new IllegalStateException("账户或资产已改变，请重新确认");
                String key = TokenWalletHelper.getCurrentPrivateKey(getApplicationContext());
                BigInteger nativeAmount = client.balance(key,address,"");
                BigInteger balance = pay==SwapAsset.BKC ? nativeAmount : client.balance(key,address,contract(pay));
                BigInteger reserve = client.gasReserve().multiply(BigInteger.valueOf(confirmed!=null && pay!=SwapAsset.BKC ? 2 : 1));
                if (balance.compareTo(input)<0 || nativeAmount.compareTo(reserve.add(pay==SwapAsset.BKC ? input : BigInteger.ZERO))<0)
                    throw new IllegalStateException("余额或手续费不足");
                if (confirmed!=null) {
                    SwapQuoteMath.Quote current = SwapQuoteMath.quote(client.pool(key),pay,out,input,confirmed.slippageBps,SystemClock.elapsedRealtime());
                    if (current.output.compareTo(confirmed.minimum)<0 || current.blocked()
                            || current.impactBps>=500 && confirmed.impactBps<500)
                        throw new IllegalStateException("价格已变动，请刷新后重新确认");
                    if (!approval && pay!=SwapAsset.BKC && client.allowance(key,address,contract(pay)).compareTo(input)<0)
                        throw new IllegalStateException("授权不足，请重新授权本次金额");
                }
                String target, data; BigInteger value = BigInteger.ZERO;
                if (approval) { target=contract(pay); data=BrokerSwapAbi.call("approve",new Address(config.router()),new Uint256(input)); }
                else if (confirmed!=null) { target=config.router(); data=BrokerSwapAbi.swap(config,confirmed,BrokerSwapConfig.walletAddress(address),System.currentTimeMillis()/1000+300); if(pay==SwapAsset.BKC)value=input; }
                else { target=contract(pay.isWrapped()?pay:out); data=pay==SwapAsset.BKC ? wrappedBkcContractHelper.encodeDeposit() : wrappedBkcContractHelper.encodeWithdraw(input); if(pay==SwapAsset.BKC)value=input; }
                TokenTxRecord record = record(pay,out,input,confirmed,approval);
                final String sendTarget=target,sendData=data; final BigInteger sendValue=value;
                main.post(() -> {
                    preparing=false;
                    if (isDestroyed() || id!=generation || !sameAccount(address)) { render(); return; }
                    if (!SwapOperationRunner.submit(getApplicationContext(),config,address,record,sendTarget,sendData,sendValue,this::completed))
                        toast("已有交易在处理中");
                    render();
                });
            } catch (Exception e) {
                main.post(() -> { preparing=false; if(!isDestroyed()) { toast(e.getMessage()==null ? "发送前检查失败，未发送" : e.getMessage()); fetch(); render(); } });
            }
        });
    }
    private TokenTxRecord record(SwapAsset pay, SwapAsset out, BigInteger input, SwapQuoteMath.Quote q, boolean approval) {
        TokenTxRecord r = new TokenTxRecord(); r.operationId=UUID.randomUUID().toString(); r.timestampMs=System.currentTimeMillis();
        r.type=approval?"APPROVAL":TokenTxRecord.TYPE_SWAP; r.fromAsset=pay.name(); r.toAsset=out.name();
        r.fromSymbol=pay.symbol; r.toSymbol=out.symbol; r.inputUnits=input.toString(); r.amountDisplay=SwapQuoteMath.format(input,pay.decimals);
        r.contractAddress=contract(pay); r.toContractAddress=approval?null:contract(out); r.routerAddress=config.router(); r.chainId=config.chainId();
        if(q!=null) { r.estimatedOutput=SwapQuoteMath.format(q.output,out.decimals); r.minimumOutput=SwapQuoteMath.format(q.minimum,out.decimals);
            r.slippageBps=q.slippageBps; r.impactBps=q.impactBps; }
        else { r.estimatedOutput=SwapQuoteMath.format(input,out.decimals); r.minimumOutput=r.estimatedOutput; }
        return r;
    }
    private void completed(TokenTxRecord record) {
        if (isDestroyed()) return;
        toast(SwapRecordPresentation.status(record.status)); clearRead(); fetch(); render();
        if ("SUCCESS".equals(record.status) && !"APPROVAL".equals(record.type)) amount.setText("");
    }
    private void resumePending() {
        if (config==null || wallet.isEmpty()) return;
        TokenTxRecord pending=unresolved();
        if (pending!=null && SwapReceipt.validHash(pending.txHash))
            SwapOperationRunner.resume(getApplicationContext(),config,wallet,pending,this::completed);
    }
    private void history() {
        List<TokenTxRecord> list = new ArrayList<>();
        for(TokenTxRecord r:TokenTxHistoryStore.getAll(this,wallet)) if(r.status!=null)list.add(r);
        if(list.isEmpty()) { toast("暂无兑换记录"); return; }
        String[] labels=new String[list.size()];
        for(int i=0;i<labels.length;i++) { TokenTxRecord r=list.get(i); labels[i]=SwapRecordPresentation.status(r.status)+" · "+r.amountDisplay+" "+r.fromSymbol; }
        new AlertDialog.Builder(this).setTitle("兑换 / 授权记录").setItems(labels,(d,which)-> {
            TokenTxRecord r=list.get(which);
            if(TokenTxHistoryStore.isUnresolvedSwap(r) && SwapReceipt.validHash(r.txHash)) {
                new AlertDialog.Builder(this).setTitle("等待确认") .setMessage(SwapRecordPresentation.details(r))
                        .setPositiveButton("再查询一次",(d2,w)-> { SwapOperationRunner.resume(getApplicationContext(),config,wallet,r,this::completed); render(); })
                        .setNegativeButton("关闭",null).show();
            } else if (TokenTxHistoryStore.isUnresolvedSwap(r) && !SwapOperationRunner.active(wallet)) {
                new AlertDialog.Builder(this).setTitle("发送结果未知")
                        .setMessage(SwapRecordPresentation.details(r) + "\n\n没有交易编号并不代表没有发出。请先核对链上记录、余额和授权。确认未重复后才可人工解除锁定。")
                        .setNegativeButton("关闭", null)
                        .setPositiveButton("我已核查链上结果", (d2,w) -> new AlertDialog.Builder(this)
                                .setTitle("解除锁定可能导致重复交易")
                                .setMessage("此操作不会撤销或重发交易，只解除本机防重复锁。你已核实原交易的最终结果吗？")
                                .setNegativeButton("保留锁定", null).setPositiveButton("已核实，解除锁定", (d3,w3) -> {
                                    r.status="ACKNOWLEDGED";
                                    if (!TokenTxHistoryStore.upsertSwap(this,wallet,r)) toast("保存失败，仍需核查");
                                    render();
                                }).show()).show();
            } else TokenTxDetailDialog.show(this,r,wallet,r.contractAddress);
        }).show();
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
