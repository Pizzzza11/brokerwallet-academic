package com.example.brokerfi.faucet;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.brokerfi.R;
import com.example.brokerfi.core.network.HTTPUtil;
import com.example.brokerfi.core.storage.StorageUtil;
import com.example.brokerfi.core.util.MyUtil;
import com.example.brokerfi.main.MainActivity;
import com.example.brokerfi.main.menu.NavigationHelper;
import com.example.brokerfi.send.SendActivity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;


public class FaucetActivity extends AppCompatActivity {

    private ImageView menu;
    private ImageView notificationBtn;
    private RelativeLayout action_bar;
    private NavigationHelper navigationHelper;
    private Button button;
    private TextView tvRules;
    private TextView tvRemainingValue;
    private TextView tvRemainingScale;
    private View fillView;
    private FrameLayout remainingContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_faucet);

        intView();
        loadFaucetRemaining();
        intEvent();
        findViewById(R.id.dashedBorderView).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setClass(FaucetActivity.this, MainActivity.class);
                startActivity(intent);
            }
        });
    }

    private void intView() {
        menu = findViewById(R.id.menu);
        notificationBtn = findViewById(R.id.notificationBtn);
        action_bar = findViewById(R.id.action_bar);
        button = findViewById(R.id.button);
        tvRules = findViewById(R.id.tv_faucet_rules);
        tvRemainingValue = findViewById(R.id.tv_faucet_remaining_value);
        tvRemainingScale = findViewById(R.id.tv_faucet_remaining_scale);
        fillView = findViewById(R.id.faucet_remaining_fill);
        remainingContainer = findViewById(R.id.faucet_remaining_container);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String result = MyUtil.claim(StorageUtil.getCurrentPrivatekey(FaucetActivity.this));
                if(result == null){
                    runOnUiThread(() -> {
                        Toast.makeText(FaucetActivity.this, R.string.faucet_toast_claim_failed, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                Toast.makeText(FaucetActivity.this, FaucetActivity.this.getString(R.string.faucet_toast_result)+result, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void intEvent(){
        navigationHelper = new NavigationHelper(menu, action_bar,this,notificationBtn);
    }

    private void loadFaucetRemaining() {
        new Thread(() -> {
            try {
                byte[] bytes = HTTPUtil.doGet("faucet_remaining", new Object());
                String json = new String(bytes, "UTF-8");
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("error")) {
                    return;
                }
                double remaining = obj.get("remaining_bkc").getAsDouble();
                double dailyLimit = obj.get("daily_limit_bkc").getAsDouble();
                int perIpPerDay = obj.get("per_ip_per_day").getAsInt();
                int perAddrPerDay = obj.get("per_addr_per_day").getAsInt();
                int perAddrTotal = obj.get("per_addr_total").getAsInt();
                runOnUiThread(() -> renderFaucetRemaining(remaining, dailyLimit, perIpPerDay, perAddrPerDay, perAddrTotal));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void renderFaucetRemaining(double remaining, double dailyLimit, int perIpPerDay, int perAddrPerDay, int perAddrTotal) {
        tvRules.setText(getString(R.string.activity_faucet_rules,
                formatBkc(dailyLimit), String.valueOf(perIpPerDay), String.valueOf(perAddrPerDay), String.valueOf(perAddrTotal)));
        tvRemainingValue.setText(getString(R.string.activity_faucet_remaining_value, formatBkc(remaining)));
        tvRemainingScale.setText(getString(R.string.activity_faucet_remaining_scale, formatBkc(dailyLimit)));

        double rawRatio = dailyLimit > 0 ? remaining / dailyLimit : 0;
        final double ratio = Math.max(0, Math.min(1, rawRatio));
        remainingContainer.post(() -> {
            int width = remainingContainer.getWidth();
            ViewGroup.LayoutParams lp = fillView.getLayoutParams();
            lp.width = (int) (width * ratio);
            fillView.setLayoutParams(lp);
        });
    }

    private String formatBkc(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult intentResult = IntentIntegrator.parseActivityResult(
                requestCode,resultCode,data
        );
        if (intentResult.getContents() != null){
            String scannedData = intentResult.getContents();
            Intent intent = new Intent(this,SendActivity.class);
            intent.putExtra("scannedData",scannedData);
            startActivity(intent);

        }
    }

    @Override
    public void onBackPressed() {
        if (navigationHelper != null && navigationHelper.isPopupVisible()) {
            navigationHelper.hidePopup();
        } else {
            super.onBackPressed();
        }
    }

}
