package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号页的「添加供应商」。
 *
 * <p>入口放在账号页顶部（用户要求：不要塞进 ⋮ 菜单，那条路径太深）。流程：
 * <ol>
 *   <li>读 {@code /api/providers} 的 {@code presets}（magpie 自带 70+ 家），
 *       去掉已经加过的</li>
 *   <li>卡片列表里挑一家 —— 每张卡带厂商 logo（{@link ProviderIcons}）</li>
 *   <li>填 API key（名字可选）</li>
 *   <li>{@code POST /api/provider/save}，body
 *       {@code {id, name, preset, key, new:true}}：magpie 按 preset 把地址、
 *       模型表、catalog 全部铺好，key 一存就能用</li>
 * </ol>
 */
final class AddProviderSheet {

    private AddProviderSheet() {
    }

    /** 打开供应商选择面板。 */
    static void open(final Activity act, final Ui ui, final Runnable onAdded) {
        new Thread(() -> {
            MagpieApi.Result r = MagpieApi.providersLast(act);
            if (!r.ok()) {
                final String why = r.why();
                act.runOnUiThread(() -> toast(act, "读不到供应商列表：" + why));
                return;
            }
            JSONObject o = r.json();
            JSONArray presets = NativeData.arr(o, "presets");
            final List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < presets.length(); i++) {
                JSONObject p = presets.optJSONObject(i);
                if (p == null || NativeData.flag(p, "added")) {
                    continue;
                }
                if (NativeData.str(p, "id", "").isEmpty()) {
                    continue;
                }
                list.add(p);
            }
            act.runOnUiThread(() -> show(act, ui, list, onAdded));
        }, "magpie-presets").start();
    }

    // ------------------------------------------------------------------

    private static void show(final Activity act, final Ui ui,
                             final List<JSONObject> presets, final Runnable onAdded) {
        if (presets.isEmpty()) {
            toast(act, "没有可添加的供应商（都加过了？）");
            return;
        }

        final Dialog dialog = new Dialog(act, R.style.Theme_Magpie_Sheet);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.col(M3.SURFACE_CONTAINER_LOW));
        float r = ui.dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(act, "navigation_bar_height"));

        TextView title = ui.text("添加供应商", 21, M3.ON_SURFACE, true);
        title.setPadding(ui.dp(4), 0, 0, ui.dp(4));
        root.addView(title);

        TextView sub = ui.text("选一家，填上它的 API key。地址和模型表 magpie 会自己铺好。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        sub.setPadding(ui.dp(4), 0, 0, ui.dp(10));
        root.addView(sub);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (final JSONObject p : presets) {
            final String id = NativeData.str(p, "id", "");
            final String name = NativeData.str(p, "name", id);
            String note = NativeData.str(p, "note", "");
            if (note.isEmpty()) {
                note = NativeData.str(p, "short", "");
            }
            if (note.isEmpty()) {
                note = id;
            }
            list.addView(row(act, ui, p, name, note, () -> {
                dialog.dismiss();
                askKey(act, ui, id, name, p, onAdded);
            }));
        }

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int screenH = act.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * 0.86f));
        }
        dialog.show();
    }

    /** 一行：厂商 logo + 名字 + 一行说明。 */
    private static View row(Activity act, Ui ui, JSONObject preset,
                            String name, String note, Runnable action) {
        LinearLayout box = ui.row();
        GradientDrawable bg = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER), 16,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.clickable(box, bg);
        box.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(7);
        box.setLayoutParams(lp);

        // 厂商 logo（没有就首字母）
        int res = ProviderIcons.ofPreset(act, preset);
        if (res != 0) {
            ImageView iv = new ImageView(act);
            iv.setImageResource(res);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        } else {
            TextView t = ui.text(ProviderIcons.initial(name), 15,
                    M3.ON_PRIMARY_CONTAINER, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(ui.round(ui.col(M3.PRIMARY_CONTAINER), 10));
            box.addView(t, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        }

        LinearLayout texts = ui.column();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(12);
        box.addView(texts, tlp);

        TextView t = ui.text(name, 14.5f, M3.ON_SURFACE, true);
        t.setSingleLine(true);
        texts.addView(t);

        TextView s = ui.text(note, 11.5f, M3.ON_SURFACE_VARIANT, false);
        s.setLineSpacing(ui.dp(1), 1f);
        s.setLayoutParams(ui.matchWrap(2));
        texts.addView(s);

        box.setOnClickListener(v -> action.run());
        return box;
    }

    /** 填 key（名字可选），然后保存。 */
    private static void askKey(final Activity act, final Ui ui,
                               final String presetId, final String presetName,
                               final JSONObject preset, final Runnable onAdded) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);

        TextView hint = ui.text("把 " + presetName + " 的 API key 贴进来。key 只存在手机的 "
                        + "magpie 数据目录里，不会上传。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        hint.setLineSpacing(ui.dp(2), 1f);
        box.addView(hint);

        final EditText key = new EditText(act);
        key.setHint("sk-…");
        key.setTextSize(13);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setGravity(Gravity.TOP | Gravity.START);
        key.setMinLines(2);
        key.setMaxLines(4);
        GradientDrawable field = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 12,
                ui.col(M3.OUTLINE_VARIANT), 1);
        key.setBackground(field);
        key.setTextColor(ui.col(M3.ON_SURFACE));
        key.setHintTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        int p = ui.dp(12);
        key.setPadding(p, p, p, p);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = ui.dp(12);
        box.addView(key, klp);

        final EditText nameInput = new EditText(act);
        nameInput.setHint("名字（可留空，默认 " + presetName + "）");
        nameInput.setTextSize(13);
        nameInput.setBackground(field);
        nameInput.setTextColor(ui.col(M3.ON_SURFACE));
        nameInput.setHintTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        nameInput.setPadding(p, p, p, p);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = ui.dp(8);
        box.addView(nameInput, nlp);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("添加 " + presetName)
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (k.isEmpty()) {
                        toast(act, "没填 key，取消添加");
                        return;
                    }
                    save(act, ui, presetId, k, nameInput.getText().toString().trim(),
                            preset, onAdded);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
        }
        dlg.show();
    }

    /** POST /api/provider/save：new + preset + key。 */
    private static void save(final Activity act, final Ui ui,
                             final String presetId, final String apiKey,
                             final String name, final JSONObject preset,
                             final Runnable onAdded) {
        toast(act, "正在保存并拉模型表…");
        new Thread(() -> {
            JSONObject body = new JSONObject();
            try {
                body.put("id", presetId);
                body.put("name", name.isEmpty() ? NativeData.str(preset, "name", "") : name);
                body.put("preset", presetId);
                body.put("key", apiKey);
                body.put("new", true);
            } catch (Exception e) {
                final String msg = e.getMessage();
                act.runOnUiThread(() -> toast(act, "拼请求失败：" + msg));
                return;
            }
            MagpieApi.Result r = MagpieApi.post(act, "/api/provider/save",
                    body.toString(), MagpieApi.WRITE_MS);
            act.runOnUiThread(() -> {
                if (!r.ok()) {
                    toast(act, "保存失败：" + r.why());
                    return;
                }
                toast(act, "已添加，正在刷新额度…");
                QuotaCache.reset();
                if (onAdded != null) {
                    onAdded.run();
                }
            });
        }, "magpie-add-provider").start();
    }

    private static void toast(Activity act, String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
