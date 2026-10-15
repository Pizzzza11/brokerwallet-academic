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

/** Assets → Trade adapter. The homepage's original Swap screen remains independent. */
public class BrokerSwapActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private BrokerSwapConfig config;
    private BrokerSwapClient client;
    private String original, wallet = "", error = "";
    private SwapAsset from = SwapAsset.BKC, to = SwapAsset.MUSDT;
    private SwapPoolSnapshot pool;
    private BigInteger nativeBalance, payBalance, receiveBalance, allowance, gasReserve;
    private long readAt, validatedAt;
    private boolean loading, confirming;
    private volatile boolean resumed, preparing;
    private volatile int generation;
    private int slippage = 50, readProgress;
    private EditText amount;
    private TextView receive, status, details, paySymbol, receiveSymbol, payBalanceView, receiveBalanceView;
    private Button submit, slipButton;
    private SwipeRefreshLayout refresh;
    private LiquidityPanelController liquidity;
    private TradeHistory swapHistory;
    private final TradeQuoteRequest quoteRequest = new TradeQuoteRequest();
    private volatile boolean liquidityVisible;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            render();
            if (!liquidityVisible && !loading && !locked() && error.isEmpty()
                    && TradeQuoteRequest.autoRefreshDue(SystemClock.elapsedRealtime(), readAt)) fetch();
            main.postDelayed(this, 15000);
        }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_swap);
        TokenTopBarHelper.bind(this);
        ((TextView)findViewById(R.id.token_page_title)).setText(R.string.trade_title);
        findViewById(R.id.token_info_button).setVisibility(View.GONE);
        findViewById(R.id.trade_tabs).setVisibility(View.VISIBLE);
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
                        quoteRequest.cancel(); slippage = new int[]{50,100,200,500}[which]; render();
                    }).show();
        });
        findViewById(R.id.broker_swap_history).setVisibility(View.GONE);
        swapHistory=new TradeHistory(this,R.id.trade_swap_history_toggle,R.id.trade_swap_history_rows,false);
        liquidity=new LiquidityPanelController(this,config,() -> confirming || preparing,this::liquidityChanged);
        findViewById(R.id.trade_tab_swap).setOnClickListener(v -> setLiquidity(false));
        findViewById(R.id.trade_tab_liquidity).setOnClickListener(v -> setLiquidity(true));
        refresh.setOnRefreshListener(() -> { if(liquidityVisible)liquidity.fetch(); else fetch(); });
        submit.setOnClickListener(v -> confirm());
        amount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { quoteRequest.cancel(); render(); }
            public void afterTextChanged(Editable s) { }
        });
        render();
        updateTabs();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        String current = BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(this));
        if (!current.equalsIgnoreCase(wallet)) {
            quoteRequest.cancel(); wallet = current; generation++; validatedAt = 0; clearRead();
        }
        liquidity.onResume(wallet);
        fetch(); resumePending(); main.removeCallbacks(tick); main.postDelayed(tick, 15000);
    }
    @Override protected void onPause() { quoteRequest.cancel(); resumed = false; generation++; main.removeCallbacks(tick); if(liquidity!=null)liquidity.onPause(); super.onPause(); }
    @Override protected void onDestroy() { quoteRequest.cancel(); resumed=false; generation++; main.removeCallbacks(tick); if(liquidity!=null)liquidity.onDestroy(); super.onDestroy(); }
    private void setLiquidity(boolean value) {
        if(locked())return;
        if(value==liquidityVisible)return;
        generation++; // Stop subsequent reads for the page that is being hidden.
        quoteRequest.cancel();
        liquidityVisible=value;
        findViewById(R.id.trade_swap_content).setVisibility(value?View.GONE:View.VISIBLE);
        findViewById(R.id.trade_liquidity_content).setVisibility(value?View.VISIBLE:View.GONE);
        liquidity.setVisible(value); updateTabs(); if(!value)fetch();
    }
    private void updateTabs() {
        findViewById(R.id.trade_tab_swap).setSelected(!liquidityVisible);
        findViewById(R.id.trade_tab_liquidity).setSelected(liquidityVisible);
        findViewById(R.id.trade_tab_swap).setEnabled(!locked());
        findViewById(R.id.trade_tab_liquidity).setEnabled(!locked());
    }
    private void liquidityChanged() {
        if(isDestroyed())return;
        if(liquidityVisible)refresh.setRefreshing(liquidity.loading());
        render(); updateTabs();
    }
    private String contract(SwapAsset asset) { return config.tokenAddress(asset, original); }
    private boolean amm() { return SwapAsset.isAmm(from, to); }
    private boolean supported() { return amm() || SwapAsset.isWrap(from, to) || SwapAsset.isUnwrap(from, to); }
    private boolean locked() { return confirming || preparing || liquidity!=null && liquidity.busy() || SwapOperationRunner.active(wallet); }
    private boolean sameAccount(String address) { return address.equalsIgnoreCase(BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(this))); }
    private boolean fresh() { return error.isEmpty() && readAt > 0 && SystemClock.elapsedRealtime() - readAt <= SwapPoolSnapshot.MAX_AGE_MS; }
    private void clearRead() { pool = null; readAt = 0; nativeBalance = payBalance = receiveBalance = allowance = gasReserve = null; }
    private void changed() { quoteRequest.cancel(); generation++; clearRead(); amount.setText(""); render(); fetch(); }
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
    private void fetch() { fetch(false); }
    private void fetch(boolean userRequested) {
        if(!resumed || liquidityVisible)return;
        if (config == null || wallet.isEmpty() || locked()) { refresh.setRefreshing(false); render(); return; }
        if (loading) return;
        loading = true; readProgress=0; error = ""; render();
        final int id = generation; final String address = wallet;
        final SwapAsset pay = from, out = to;
        final boolean needsProtocol = pay == SwapAsset.SWAP_WBKC || out == SwapAsset.SWAP_WBKC || pay == SwapAsset.MUSDT || out == SwapAsset.MUSDT;
        final boolean validate = needsProtocol && (validatedAt == 0 || SystemClock.elapsedRealtime()-validatedAt > 600000);
        TradeReadSession session=new TradeReadSession(
                () -> resumed && !liquidityVisible && !locked() && id==generation && sameAccount(address),
                number -> main.post(() -> {
                    if(resumed && !liquidityVisible && id==generation && sameAccount(address)) {
                        readProgress=number; render();
                    }
                }));
        BrokerSwapClient readClient=new BrokerSwapClient(config,session);
        Runnable work = () -> {
            String stage = "账户";
            try {
                session.check();
                String key = TokenWalletHelper.getCurrentPrivateKey(getApplicationContext());
                if (!sameAccount(address)) throw new IllegalStateException("账户已切换");
                if (validate) {
                    stage = "合约校验";
                    readClient.validate(key);
                    final long checkedAt = SystemClock.elapsedRealtime();
                    main.post(() -> {
                        // Metadata/code validation succeeded independently of later volatile reads.
                        // A failed reserve/balance read must not restart all eight immutable checks.
                        if (!isDestroyed() && resumed && !liquidityVisible && id == generation && sameAccount(address)) {
                            validatedAt = checkedAt;
                            importProtocolTokens();
                        }
                    });
                }
                stage = "BKC 余额";
                BigInteger nativeAmount = readClient.balance(key, address, "");
                stage = "支付资产余额";
                BigInteger payAmount = pay == SwapAsset.BKC ? nativeAmount : readClient.balance(key, address, contract(pay));
                stage = "到账资产余额";
                BigInteger receiveAmount = out == SwapAsset.BKC ? nativeAmount : readClient.balance(key, address, contract(out));
                stage = "手续费";
                BigInteger gas = readClient.gasReserve();
                stage = "授权额度";
                BigInteger approved = SwapAsset.isAmm(pay, out) && pay != SwapAsset.BKC
                        ? readClient.allowance(key, address, contract(pay)) : BigInteger.ZERO;
                // Pool calls on Dash may wait for execution. Capture the quote LAST so balance/allowance
                // reads cannot consume its 45-second validity before it reaches the screen.
                stage = "资金池";
                SwapPoolSnapshot snapshot = SwapAsset.isAmm(pay, out) ? readClient.pool(key) : null;
                main.post(() -> {
                    loading = false; if(!liquidityVisible)refresh.setRefreshing(false);
                    if (isDestroyed()) return;
                    if (!resumed || liquidityVisible || id != generation || !sameAccount(address)) { fetch(); return; }
                    pool = snapshot; nativeBalance = nativeAmount; payBalance = payAmount; receiveBalance = receiveAmount;
                    gasReserve = gas; allowance = approved;
                    readAt = snapshot == null ? SystemClock.elapsedRealtime() : snapshot.capturedAtMs; error = "";
                    render();
                    if (quoteRequest.take() != null && !locked()) {
                        if (fresh()) confirm();
                        else { readAt=0; error="读取结果已过期，请再次获取最新报价；未发送交易。"; render(); }
                    }
                });
            } catch (Exception e) {
                final String failedStage = stage, reason=TradeReadFailure.describe(e);
                final boolean cancelled=e instanceof TradeReadSession.Cancelled;
                main.post(() -> {
                    loading = false; if(!liquidityVisible)refresh.setRefreshing(false);
                    if (isDestroyed()) return;
                    if(cancelled || !resumed || liquidityVisible || id!=generation || !sameAccount(address)) { fetch(); return; }
                    quoteRequest.cancel(); readAt = 0;
                    error = failedStage + "："+reason+"。旧数据仅供参考，请获取最新报价；此次读取不发送资金交易。"; render();
                });
            }
        };
        if (userRequested) TradeReadQueue.executeUser(work); else TradeReadQueue.execute(work);
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
    private String loadingText() {
        return readProgress==0?"查询排队中…": "正在查询链上数据（第 "+readProgress+" 项，首次可能需数分钟）…";
    }
    private BigInteger requiredGas() {
        if (gasReserve == null) return BigInteger.ZERO;
        return amm() && from != SwapAsset.BKC ? gasReserve.multiply(BigInteger.valueOf(2)) : gasReserve;
    }
    private TokenTxRecord unresolved() {
        for (TokenTxRecord r : TokenTxHistoryStore.getAll(this, wallet)) if (TokenTxHistoryStore.isBlockingSwap(r)) return r;
        return null;
    }
    private TokenTxRecord deferred() {
        for(TokenTxRecord r:TokenTxHistoryStore.getAll(this,wallet))if(TradeRecoveryPolicy.deferred(r))return r;
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
        String cached = fresh() ? "" : "（旧数据）";
        payBalanceView.setText("余额：" + TradeDisplay.amount(payBalance,from.decimals) + (payBalance == null ? "" : cached));
        receiveBalanceView.setText("余额：" + TradeDisplay.amount(receiveBalance,to.decimals) + (receiveBalance == null ? "" : cached));
        slipButton.setText("Slippage：" + slippage/100.0 + "% ▾"); slipButton.setEnabled(!locked() && amm());
        amount.setEnabled(!locked());
        TokenTxRecord pending=unresolved();
        String operation = TradeStatusText.operation(confirming, preparing, SwapOperationRunner.active(wallet), pending);
        status.setText("mUSDT 是测试资产，两种 wBKC 不可混用。\n" + (wallet.isEmpty() ? "请先登录钱包" : !operation.isEmpty() ? operation
                : loading ? loadingText() : error.isEmpty() ? "网络 1051" : error)
                +(pending!=null ? loading?"\n"+loadingText():!error.isEmpty()?"\n"+error:"":"")
                +(deferred()!=null?"\n旧记录仍待核查，已接受风险继续（点击查看）":""));
        status.setTextColor(pending!=null || !error.isEmpty()?0xff991B1B:0xff475569);
        status.setOnClickListener(v -> { TokenTxRecord r=unresolved(); if(r==null)r=deferred(); if(r!=null)TradeHistory.showRecord(this,config,wallet,r,() -> { render(); fetch(); }); });
        if(swapHistory!=null)swapHistory.bind(config,wallet,() -> { render(); fetch(); });
        updateTabs();
        receive.setText("—"); details.setText(""); submit.setEnabled(false);
        if (config == null || wallet.isEmpty()) { submit.setText("钱包或部署未就绪"); return; }
        try {
            BigInteger input = SwapQuoteMath.parseUnits(amount.getText().toString(), from.decimals);
            SwapQuoteMath.Quote q = amm() && pool != null ? SwapQuoteMath.reference(pool,from,to,input,slippage) : null;
            BigInteger output = q==null ? amm() ? null : input : q.output;
            receive.setText(TradeDisplay.amount(output,to.decimals));
            details.setText(q==null ? amm() ? "等待最新报价" : "Rate：1 : 1 · 包装 / 解包\nMin received："+TradeDisplay.amount(input,to.decimals)+" "+to.symbol
                    : "Rate："+TradeDisplay.rate(q)+"\nMin received："+TradeDisplay.amount(q.minimum,q.to.decimals)+" "+q.to.symbol
                        +(q.impactBps>=200?"\n价格影响："+q.impactBps/100.0+"%（较高风险）":"")
                        +(!fresh()?"\n旧报价，仅供参考；发送前必须重新核价":""));
            if (!supported()) submit.setText("这两种资产不能直接兑换");
            else if (!fresh()) {
                submit.setText(quoteRequest.pending() ? "正在获取最新报价，尚未发送" : "获取最新报价");
                submit.setEnabled(!locked() && pending==null && !quoteRequest.pending());
            } else {
                String problem = validation(input,q);
                submit.setText(!problem.isEmpty() ? problem : needsApproval(input) ? "Approve（仅本次金额）" : "Swap");
                submit.setEnabled(problem.isEmpty() && !locked());
            }
        } catch (Exception e) { submit.setText(amount.getText().length()==0 || "0".contentEquals(amount.getText())
                ? "请输入金额" : "金额精度不符或金额太小"); }
        if (!operation.isEmpty() || locked()) {
            submit.setText(operation.isEmpty() ? "其他操作检查中，请稍候" : operation); submit.setEnabled(false);
        }
        TradeButtons.state(submit,submit.getText().toString(),submit.isEnabled(),pending!=null || !error.isEmpty() || !loading && readAt>0 && !fresh());
    }
    private String quoteDetails(SwapQuoteMath.Quote q) {
        return "预计到账：" + SwapQuoteMath.format(q.output,q.to.decimals) + " " + q.to.symbol
                + "\n最低到账：" + SwapQuoteMath.format(q.minimum,q.to.decimals) + " " + q.to.symbol
                + "\n兑换比例：" + TradeDisplay.rate(q) + "\n滑点：" + q.slippageBps/100.0 + "%"
                + "\n兑换费：0.3%（报价已包含）　价格影响：" + q.impactBps/100.0 + "%"
                + (q.impactBps>=200 ? "\n警告：交易量相对资金池较大" : "")
                + "\n手续费预留：" + SwapQuoteMath.format(requiredGas(),18) + " BKC（估算，非保证）";
    }
    private void confirm() {
        if (locked()) return;
        try {
            BigInteger input = SwapQuoteMath.parseUnits(amount.getText().toString(),from.decimals);
            if (!sameAccount(wallet) || unresolved()!=null || !supported()) { toast("请核对账户、资产或未确认交易"); return; }
            if (!fresh()) {
                quoteRequest.request("SWAP"); fetch(true); render(); return;
            }
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
        TradeReadQueue.executeUser(() -> {
            String stage = "账户状态";
            try {
                if (id!=generation || !sameAccount(address)) throw new IllegalStateException("账户或资产已改变，请重新确认");
                String key = TokenWalletHelper.getCurrentPrivateKey(getApplicationContext());
                stage = "BKC 余额";
                BigInteger nativeAmount = client.balance(key,address,"");
                stage = "支付资产余额";
                BigInteger balance = pay==SwapAsset.BKC ? nativeAmount : client.balance(key,address,contract(pay));
                stage = "手续费";
                BigInteger reserve = client.gasReserve().multiply(BigInteger.valueOf(confirmed!=null && pay!=SwapAsset.BKC ? 2 : 1));
                if (balance.compareTo(input)<0 || nativeAmount.compareTo(reserve.add(pay==SwapAsset.BKC ? input : BigInteger.ZERO))<0)
                    throw new IllegalStateException("余额或手续费不足");
                SwapPoolSnapshot latestPool=null;
                if (confirmed!=null) {
                    // Read allowance BEFORE reserves, so a slow signed read cannot age the final quote.
                    stage = "授权额度";
                    if (!approval && pay!=SwapAsset.BKC && client.allowance(key,address,contract(pay)).compareTo(input)<0)
                        throw new IllegalStateException("授权不足，请重新授权本次金额");
                    stage = "最新资金池报价";
                    latestPool=client.pool(key);
                    SwapQuoteMath.Quote current = SwapQuoteMath.quote(latestPool,pay,out,input,confirmed.slippageBps,SystemClock.elapsedRealtime());
                    if (current.output.compareTo(confirmed.minimum)<0 || current.blocked()
                            || current.impactBps>=500 && confirmed.impactBps<500)
                        throw new IllegalStateException("价格已变动，请刷新后重新确认");
                }
                if(latestPool!=null && !latestPool.isFresh(SystemClock.elapsedRealtime()))throw new IllegalStateException("Quote expired");
                stage = "交易编码";
                String target, data; BigInteger value = BigInteger.ZERO;
                long deadline=confirmed!=null && !approval?System.currentTimeMillis()/1000+300:0;
                if (approval) { target=contract(pay); data=BrokerSwapAbi.call("approve",new Address(config.router()),new Uint256(input)); }
                else if (confirmed!=null) { target=config.router(); data=BrokerSwapAbi.swap(config,confirmed,BrokerSwapConfig.walletAddress(address),deadline); if(pay==SwapAsset.BKC)value=input; }
                else { target=contract(pay.isWrapped()?pay:out); data=pay==SwapAsset.BKC ? wrappedBkcContractHelper.encodeDeposit() : wrappedBkcContractHelper.encodeWithdraw(input); if(pay==SwapAsset.BKC)value=input; }
                TokenTxRecord record = record(pay,out,input,confirmed,approval);
                record.deadlineSeconds=deadline;
                final String sendTarget=target,sendData=data; final BigInteger sendValue=value;
                final SwapPoolSnapshot finalPool=latestPool;
                main.post(() -> {
                    preparing=false;
                    if (isDestroyed() || id!=generation || !sameAccount(address)) { render(); return; }
                    if(finalPool!=null && !finalPool.isFresh(SystemClock.elapsedRealtime())) { readAt=0; error="检查结果已过期，未发交易。请获取最新报价后重新确认。"; render(); return; }
                    if (!SwapOperationRunner.submit(getApplicationContext(),config,address,record,sendTarget,sendData,sendValue,this::completed))
                        toast("已有交易在处理中");
                    render();
                });
            } catch (Exception e) {
                final String failedStage = stage;
                main.post(() -> {
                    preparing=false;
                    if(!isDestroyed()) {
                        readAt=0; error="发送前的"+failedStage+"检查未通过，未发交易。请获取最新报价后重新确认。";
                        toast(error); render();
                    }
                });
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
        toast("APPROVAL".equals(record.type) && "SUCCESS".equals(record.status)
                ? "授权成功，请核对报价后单独确认兑换" : SwapRecordPresentation.status(record.status));
        quoteRequest.cancel(); readAt=0; fetch(); render();
        liquidity.changed();
        if ("SUCCESS".equals(record.status) && !"APPROVAL".equals(record.type)) amount.setText("");
    }
    private void resumePending() {
        if (config==null || wallet.isEmpty()) return;
        TokenTxRecord pending=unresolved();
        if (pending!=null && SwapReceipt.validHash(pending.txHash))
            SwapOperationRunner.resume(getApplicationContext(),config,wallet,pending,this::completed);
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
