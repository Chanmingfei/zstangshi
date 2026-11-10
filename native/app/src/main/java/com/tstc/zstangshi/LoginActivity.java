package com.tstc.zstangshi;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.jw.Auth;
import com.tstc.zstangshi.widget.TodayWidget;

/**
 * 登录页。
 *
 * 版式照旧版网页还原：整屏居中 —— 上面是 Logo + 校名标语，下面一张白卡片装表单
 * （学号、密码、记住密码/自动登录、错误条、登录按钮），卡片外面是「先看看课表」
 * 和一行隐私说明。
 *
 * 交互口径（2026-11-10 定）：
 *   · 勾了「自动登录」就一定要记住密码，两个勾互相咬住；
 *   · 登录成功才写盘，失败的输入留在框里不丢；
 *   · 底部的「先看看课表」是逃生门：教务连不上时，本地已有的课表不该被挡住；
 *   · 返回键直接退到桌面。
 */
public class LoginActivity extends BaseActivity {

    public static final String EXTRA_MESSAGE = "message";

    /** true = 从「我的」进来补登录，成功后原地返回，而不是再开一个 MainActivity。 */
    public static final String EXTRA_RETURN = "return_to_caller";

    private Store store;
    private boolean returnMode;
    private EditText idInput;
    private EditText pwdInput;
    /** 密码当前是否明文显示（显隐切换的显式状态，不再靠 inputType 位运算猜）。 */
    private boolean pwdShown;
    private CheckBox remember;
    private CheckBox auto;
    private TextView error;
    private TextView submit;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        returnMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_RETURN, false);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pageColor());
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(28));
        scroll.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 品牌：校徽＋校名横版图 + 应用名 + 标语（与旧版登录页一致） ──
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.login_badge);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(210),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(24);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text_primary));
        name.setLetterSpacing(0.04f);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(14), 0, 0);
        brand.addView(name);

        TextView slogan = new TextView(this);
        slogan.setText("唐山师范学院 · 教务数据随身看");
        slogan.setTextSize(12.5f);
        slogan.setTextColor(getColor(R.color.text_secondary));
        slogan.setGravity(Gravity.CENTER);
        slogan.setPadding(0, dp(6), 0, dp(22));
        brand.addView(slogan);
        page.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 表单卡片 ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        card.setBackground(getDrawable(R.drawable.card_bg));
        card.setElevation(dp(2));

        card.addView(fieldLabel("学号"));
        idInput = new EditText(this);
        idInput.setHint("10-12 位学号");
        idInput.setHintTextColor(getColor(R.color.text_tertiary));
        idInput.setTextColor(getColor(R.color.text_primary));
        idInput.setTextSize(15);
        idInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        idInput.setBackground(getDrawable(R.drawable.input_bg));
        idInput.setPadding(dp(14), dp(13), dp(14), dp(13));
        idInput.setSingleLine(true);
        idInput.setText(store.user());
        card.addView(idInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView pwdLabel = fieldLabel("密码");
        pwdLabel.setPadding(0, dp(14), 0, dp(6));
        card.addView(pwdLabel);

        LinearLayout pwdRow = new LinearLayout(this);
        pwdRow.setOrientation(LinearLayout.HORIZONTAL);
        pwdRow.setGravity(Gravity.CENTER_VERTICAL);
        pwdRow.setBackground(getDrawable(R.drawable.input_bg));
        pwdRow.setPadding(dp(14), 0, dp(6), 0);

        pwdInput = new EditText(this);
        pwdInput.setHint("教务系统密码");
        pwdInput.setHintTextColor(getColor(R.color.text_tertiary));
        pwdInput.setTextColor(getColor(R.color.text_primary));
        pwdInput.setTextSize(15);
        // 注意顺序：setSingleLine 会重置输入类型、清掉密码掩码（默认变明文），
        // 必须先调它，最后才设密码类型，保证初始就是密文、和眼睛初始图标一致
        pwdInput.setSingleLine(true);
        pwdInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pwdInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdInput.setBackground(null);
        pwdInput.setPadding(0, dp(13), 0, dp(13));
        boolean remembered = store.rememberPwd() && !store.pass().isEmpty();
        if (remembered) pwdInput.setText(store.pass());
        pwdRow.addView(pwdInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 眼睛按钮（照老版 Web 的 eye / eyeOff 图标）：显隐状态用显式布尔记录。
        // 之前用 inputType 按位与判断，VISIBLE_PASSWORD 和 PASSWORD 的位有重叠，
        // 判定永远是「隐藏中」，所以点第一次能显示、之后再也切不回去。
        pwdShown = false;
        android.widget.ImageView eye = new android.widget.ImageView(this);
        eye.setImageResource(R.drawable.ic_eye);
        eye.setPadding(dp(8), dp(8), dp(8), dp(8));
        eye.setContentDescription("显示密码");
        eye.setOnClickListener(v -> {
            pwdShown = !pwdShown;
            if (pwdShown) {
                pwdInput.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                eye.setImageResource(R.drawable.ic_eye_off);
                eye.setContentDescription("隐藏密码");
            } else {
                pwdInput.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                eye.setImageResource(R.drawable.ic_eye);
                eye.setContentDescription("显示密码");
            }
            // setInputType 会把字体重置成等宽，摆回去；光标挪到末尾
            pwdInput.setTypeface(Typeface.DEFAULT);
            pwdInput.setSelection(pwdInput.getText().length());
        });
        pwdRow.addView(eye);
        card.addView(pwdRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 记住密码 / 自动登录 ──
        LinearLayout checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.HORIZONTAL);
        checks.setGravity(Gravity.CENTER_VERTICAL);
        checks.setPadding(0, dp(12), 0, 0);

        remember = check("记住密码", store.rememberPwd() || remembered);
        auto = check("自动登录", store.autoLogin());
        checks.addView(remember, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        checks.addView(auto);
        card.addView(checks);

        remember.setOnCheckedChangeListener((v, on) -> {
            if (!on && auto.isChecked()) auto.setChecked(false);
        });
        auto.setOnCheckedChangeListener((v, on) -> {
            if (on && !remember.isChecked()) remember.setChecked(true);
        });

        // ── 错误条 ──
        error = new TextView(this);
        error.setTextSize(12.5f);
        error.setTextColor(0xFFB3261E);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0xFFFDECEA);
        eg.setCornerRadius(dp(10));
        error.setBackground(eg);
        error.setPadding(dp(12), dp(9), dp(12), dp(9));
        error.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(12);
        card.addView(error, elp);

        String msg = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MESSAGE);
        if (msg != null && !msg.isEmpty()) {
            error.setText(msg);
            error.setVisibility(android.view.View.VISIBLE);
        }

        // ── 登录按钮 ──
        submit = new TextView(this);
        submit.setText("登 录");
        submit.setTextSize(15.5f);
        submit.setTypeface(Typeface.DEFAULT_BOLD);
        submit.setGravity(Gravity.CENTER);
        submit.setTextColor(0xFFFFFFFF);
        submit.setBackground(getDrawable(R.drawable.btn_primary));
        submit.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(16);
        card.addView(submit, slp);
        submit.setOnClickListener(v -> doLogin());

        page.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 仅登录学工系统：绕开教务的侧门，不建立 App 登录态 ──
        LinearLayout alt = new LinearLayout(this);
        alt.setOrientation(LinearLayout.HORIZONTAL);
        alt.setGravity(Gravity.CENTER_VERTICAL);
        alt.setBackground(getDrawable(R.drawable.card_bg));
        alt.setPadding(dp(14), dp(13), dp(14), dp(13));
        alt.setClickable(true);
        alt.setOnClickListener(v -> com.tstc.zstangshi.WebViewActivity.open(this,
                "http://sws.tstc.edu.cn/XGTSTC/Phone/#/login", "学工系统"));

        TextView altIcon = new TextView(this);
        altIcon.setText("↗");
        altIcon.setTextSize(18);
        altIcon.setTypeface(Typeface.DEFAULT_BOLD);
        altIcon.setGravity(Gravity.CENTER);
        altIcon.setTextColor(getColor(R.color.brand));
        android.graphics.drawable.GradientDrawable iconBg =
                new android.graphics.drawable.GradientDrawable();
        iconBg.setColor(getColor(R.color.brand_soft));
        iconBg.setCornerRadius(dp(10));
        altIcon.setBackground(iconBg);
        alt.addView(altIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout altText = new LinearLayout(this);
        altText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams altTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        altTextLp.leftMargin = dp(12);
        TextView altTitle = new TextView(this);
        altTitle.setText("仅登录学工系统");
        altTitle.setTextSize(14);
        altTitle.setTypeface(Typeface.DEFAULT_BOLD);
        altTitle.setTextColor(getColor(R.color.text_primary));
        altText.addView(altTitle);
        TextView altDesc = new TextView(this);
        altDesc.setText("奖助贷、请假、综合素质 · 免教务登录");
        altDesc.setTextSize(11);
        altDesc.setTextColor(getColor(R.color.text_tertiary));
        altDesc.setPadding(0, dp(2), 0, 0);
        altText.addView(altDesc);
        alt.addView(altText, altTextLp);

        TextView altArrow = new TextView(this);
        altArrow.setText("›");
        altArrow.setTextSize(22);
        altArrow.setTextColor(getColor(R.color.text_tertiary));
        alt.addView(altArrow);

        LinearLayout.LayoutParams altLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        altLp.topMargin = dp(12);
        page.addView(alt, altLp);

        // ── 逃生门（从「我的」进来时没意义：本就在应用里） ──
        TextView skip = new TextView(this);
        skip.setText(store.hasLocal() ? "先看看课表 ›" : "暂时不登录，直接进应用 ›");
        skip.setTextSize(13);
        skip.setTextColor(getColor(R.color.text_secondary));
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(18), 0, 0);
        skip.setOnClickListener(v -> goMain());
        if (returnMode) skip.setVisibility(android.view.View.GONE);
        page.addView(skip);

        TextView note = new TextView(this);
        note.setText("密码用教务系统的 RSA 公钥加密后提交，本地只留一份用于自动登录");
        note.setTextSize(11);
        note.setTextColor(getColor(R.color.text_secondary));
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(22), dp(8), 0);
        page.addView(note);

        setContentView(scroll);
    }

    private TextView fieldLabel(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(12.5f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(getColor(R.color.text_secondary));
        v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(13);
        c.setTextColor(getColor(R.color.text_secondary));
        c.setChecked(on);
        c.setButtonTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}},
                new int[]{getColor(R.color.brand), getColor(R.color.text_tertiary)}));
        return c;
    }

    // ---------- 登录 ----------

    private void doLogin() {
        String u = idInput.getText().toString().trim();
        final String p = pwdInput.getText().toString();
        if (u.isEmpty()) {
            showError("先填学号");
            return;
        }
        if (p.isEmpty()) {
            showError("先填密码");
            return;
        }
        hideError();
        setBusy(true, "登录中…");

        new Thread(() -> {
            Auth.Outcome o = Auth.loginAndMaybeSync(u, p, store, true);
            runOnUiThread(() -> {
                setBusy(false, "登 录");
                if (!o.ok) {
                    showError(o.message);
                    return;
                }
                persist(u, p);
                TodayWidget.refreshAll(this);
                goMain();
            });
        }).start();
    }

    /** 按勾选落盘：记住密码才存密码，否则只留学号。 */
    private void persist(String u, String p) {
        boolean keepPwd = remember.isChecked() || auto.isChecked();
        store.setRememberPwd(keepPwd);
        store.setAutoLogin(auto.isChecked());
        if (keepPwd) store.saveLogin(u, p);
        else store.saveUser(u);
    }

    private void setBusy(boolean busy, String label) {
        submit.setEnabled(!busy);
        submit.setAlpha(busy ? 0.6f : 1f);
        submit.setText(label);
    }

    private void showError(String s) {
        error.setText(s == null || s.isEmpty() ? "登录失败，请再试一次" : s);
        error.setVisibility(android.view.View.VISIBLE);
    }

    private void hideError() {
        error.setVisibility(android.view.View.GONE);
    }

    private void goMain() {
        if (returnMode) {
            // 从「我的」进来的：回到原来的 MainActivity，界面自己刷新
            finish();
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }
}
