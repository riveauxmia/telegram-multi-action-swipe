package org.telegram.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ConceptsDemo;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Autopilot for recording the demo video (scenario `all`, 20 s). A `ready` file is written, the script waits for `go`,
 * shows a sync flash (a magenta strip at the bottom), then plays real touches (MotionEvent through the activity window,
 * one move per frame), writes a touches.json log for the tap rings and a `done` file.
 * The files live in the app's external files dir (visible to adb): Android/data/<package>/files/concepts/.
 * Log times are counted from the flash; coordinates are screen pixels / 3.
 */
public class ConceptsSwipeAutopilot {

    private static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "concepts");
        d.mkdirs();
        return d;
    }

    private static final JSONArray events = new JSONArray();
    private static long base;
    private static boolean finished;
    private static long downTime;
    private static final ArrayList<Integer> received = new ArrayList<>();
    private static final ArrayList<ImageReceiver> warmers = new ArrayList<>();

    public static void start(int account, String scenario) {
        File d = dir();
        new File(d, "ready").delete();
        new File(d, "go").delete();
        new File(d, "done").delete();
        finished = false;
        hideCaret();
        // Сцена успевает загрузиться (история, тема, аватарки), потом сигнал «готов» и ожидание записи.
        // Прогрев: стикер ☃️ (Lottie) и тема чата Lena — иначе в ролике они появляются с задержкой.
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.Document doc = ConceptsDemo.sticker(account, "snowman.tgs");
            AndroidUtilities.runOnUIThread(() -> {
                ImageReceiver warm = new ImageReceiver();
                warm.setAllowLoadingOnAttachedOnly(false);
                warm.setImage(ImageLocation.getForDocument(doc), "160_160", null, "tgs", doc, 1);
                warmers.add(warm);
            });
        });
        AndroidUtilities.runOnUIThread(() -> {
            centerFirst();
            write(new File(d, "ready"), "ready");
            waitGo(account, scenario);
        }, 6000);
    }

    /** Курсор в поле ввода мигает и отвлекает — в демо прячем его во всех чатах до конца записи. */
    private static void hideCaret() {
        ChatActivity c = chat();
        ChatActivityEnterView enter = c != null ? c.getChatActivityEnterView() : null;
        if (enter != null && enter.getEditField() != null) {
            enter.getEditField().setCursorVisible(false);
        }
        if (!finished) {
            AndroidUtilities.runOnUIThread(ConceptsSwipeAutopilot::hideCaret, 50);
        }
    }

    private static void waitGo(int account, String scenario) {
        File go = new File(dir(), "go");
        if (go.exists()) {
            go.delete();
            play(account, scenario);
        } else {
            AndroidUtilities.runOnUIThread(() -> waitGo(account, scenario), 50);
        }
    }

    private static Activity activity() {
        return LaunchActivity.instance;
    }

    private static ChatActivity chat() {
        BaseFragment f = LaunchActivity.getLastFragment();
        return f instanceof ChatActivity ? (ChatActivity) f : null;
    }

    private static double t() {
        return (SystemClock.uptimeMillis() - base) / 1000.0;
    }

    private static void after(double seconds, Runnable r) {
        long at = base + (long) (seconds * 1000);
        AndroidUtilities.runOnUIThread(r, Math.max(0, at - SystemClock.uptimeMillis()));
    }

    // region журнал

    private static void event(String type, JSONObject extra) {
        try {
            JSONObject o = extra != null ? extra : new JSONObject();
            o.put("t", t());
            o.put("type", type);
            events.put(o);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void mark(String name) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            event("mark", o);
        } catch (Exception ignore) {
        }
    }

    private static void ring(float x, float y, boolean longTap) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", longTap ? "long" : "tap");
            o.put("x_pt", x / 3.0);
            o.put("y_pt", y / 3.0);
            event("ring", o);
        } catch (Exception ignore) {
        }
    }

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void writeLog() {
        try {
            JSONObject root = new JSONObject();
            root.put("events", events);
            write(new File(dir(), "touches.json"), root.toString(1));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // endregion

    // region касания

    private static void inject(int action, float x, float y) {
        Activity a = activity();
        if (a == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now;
        }
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        a.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Тап с кольцом, как realTap iOS: кольцо, касание через 0,25 с, отпускание ещё через 0,167 с. */
    private static void tap(float x, float y) {
        ring(x, y, false);
        AndroidUtilities.runOnUIThread(() -> inject(MotionEvent.ACTION_DOWN, x, y), 250);
        AndroidUtilities.runOnUIThread(() -> inject(MotionEvent.ACTION_UP, x, y), 417);
    }

    interface Track {
        float[] at(double t); // смещение пальца (dx, dy) в пикселях
    }

    /**
     * Лонгтап-жест: кольцо за 0,25 с до касания, идёт за пальцем (метки pt|t|x|y), гаснет при отпускании (ringEnd).
     * Палец движется по кадрам экрана (Choreographer): ровно одно движение на кадр.
     */
    private static void gesture(float sx, float sy, double start, double end, Track track, double releaseDelay) {
        float[] p0 = track.at(start);
        AndroidUtilities.runOnUIThread(() -> ring(sx + p0[0], sy + p0[1], true), (long) Math.max(0, (start - 0.25) * 1000));
        for (double k = start; k < end + 1e-6; k += 1.0 / 30.0) {
            final double key = k;
            AndroidUtilities.runOnUIThread(() -> {
                float[] p = track.at(key);
                mark("pt|" + key + "|" + (sx + p[0]) / 3.0 + "|" + (sy + p[1]) / 3.0);
            }, (long) (key * 1000));
        }
        AndroidUtilities.runOnUIThread(() -> {
            long t0 = SystemClock.uptimeMillis() - (long) (start * 1000);
            inject(MotionEvent.ACTION_DOWN, sx + p0[0], sy + p0[1]);
            Choreographer.FrameCallback[] cb = new Choreographer.FrameCallback[1];
            cb[0] = frameTimeNanos -> {
                double local = (SystemClock.uptimeMillis() - t0) / 1000.0;
                if (local >= end) {
                    float[] p = track.at(end);
                    inject(MotionEvent.ACTION_MOVE, sx + p[0], sy + p[1]);
                    event("ringEnd", null);
                    AndroidUtilities.runOnUIThread(() -> inject(MotionEvent.ACTION_UP, sx + p[0], sy + p[1]), (long) (releaseDelay * 1000));
                    return;
                }
                float[] p = track.at(local);
                inject(MotionEvent.ACTION_MOVE, sx + p[0], sy + p[1]);
                Choreographer.getInstance().postFrameCallback(cb[0]);
            };
            Choreographer.getInstance().postFrameCallback(cb[0]);
        }, (long) (start * 1000));
    }

    // endregion

    // region цели

    private static float[] location(View v, float fx, float fy) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new float[]{loc[0] + v.getWidth() * fx, loc[1] + v.getHeight() * fy};
    }

    /** Точка на пузыре сообщения: x — доля ширины экрана (как iOS 0,58 / 0,62), y — середина видимой части пузыря. */
    private static float[] message(int id, float fx) {
        ChatActivity chat = chat();
        if (chat == null) {
            return null;
        }
        RecyclerListView list = chat.getChatListView();
        int[] listLoc = new int[2];
        list.getLocationOnScreen(listLoc);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof ChatMessageCell && ((ChatMessageCell) child).getMessageObject() != null && ((ChatMessageCell) child).getMessageObject().getId() == id) {
                int[] loc = new int[2];
                child.getLocationOnScreen(loc);
                float top = Math.max(loc[1], listLoc[1] + AndroidUtilities.dp(56));
                float bottom = Math.min(loc[1] + child.getHeight(), listLoc[1] + list.getHeight() - AndroidUtilities.dp(56));
                return new float[]{AndroidUtilities.displaySize.x * fx, (top + bottom) / 2f};
            }
        }
        return null;
    }

    private static View find(View root, Class<?> type, long dialogId) {
        if (type.isInstance(root) && root.isShown()) {
            if (!(root instanceof DialogCell) || ((DialogCell) root).getDialogId() == dialogId) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = find(g.getChildAt(i), type, dialogId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Кадр «сообщение 3 по центру окна», как первый кадр iOS. */
    private static void centerFirst() {
        ChatActivity chat = chat();
        if (chat != null) {
            chat.scrollToMessageId(3, 0, false, 0, true, 0);
        }
    }

    // endregion

    private static void flash() {
        Activity a = activity();
        if (a == null) {
            return;
        }
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View bar = new View(a);
        bar.setBackgroundColor(Color.MAGENTA);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(40));
        int compact = ConceptsDemo.compactPx;
        if (compact > 0) {
            lp.gravity = android.view.Gravity.TOP;   // низ укороченного интерфейса, не экрана
            lp.topMargin = compact - AndroidUtilities.dp(40);
        } else {
            lp.gravity = android.view.Gravity.BOTTOM;
        }
        decor.addView(bar, lp);
        AndroidUtilities.runOnUIThread(() -> decor.removeView(bar), 100);
    }

    private static void play(int account, String scenario) {
        base = SystemClock.uptimeMillis();
        finished = false;
        event("flash", null);
        flash();
        float dp = AndroidUtilities.dp(1);
        float threshold = 50 * dp;
        // Заход за порог ответа, как iOS (+14). Android считает сдвиг только после первых 0,4 см движения — их добавляем.
        float past = -(threshold + 14 * dp + AndroidUtilities.getPixelsInCM(0.4f, true));
        float reach = 48 * dp;
        double length = 20.0;

        // Чат 1 (Sofia): один жест — влево (Reply), вниз до нижнего слота (Copy), вверх до Forward, отпустить → выбор чата.
        after(0.2, () -> {
            float[] p = message(3, 0.58f);
            if (p == null) {
                return;
            }
            double[][] xKeys = {{0.3, 0}, {1.0, 1}, {3.3, 1}};
            double[][] yKeys = {{0.3, 0}, {1.0, 0}, {1.9, reach}, {3.2, -reach}};
            gesture(p[0], p[1], 0.3, 3.3, time -> arc(xKeys, yKeys, time, past, reach), 0.2);
        });
        after(4.5, () -> {
            View row = activity() == null ? null : find(activity().getWindow().getDecorView(), DialogCell.class, ConceptsDemo.LENA);
            float[] p = row != null ? location(row, 0.4f, 0.5f) : new float[]{AndroidUtilities.displaySize.x * 0.4f, AndroidUtilities.dp(250)};
            mark("ringLight");
            mark("pickLena");
            tap(p[0], p[1]);
        });
        after(5.8, () -> {
            // Кнопка «отправить» — справа в нижней строке поля ввода (над ней панель пересылки).
            ChatActivity chat = chat();
            View enter = chat != null ? chat.getChatActivityEnterView() : null;
            float[] p;
            if (enter != null) {
                int[] loc = new int[2];
                enter.getLocationOnScreen(loc);
                p = new float[]{loc[0] + enter.getWidth() - AndroidUtilities.dp(30), loc[1] + enter.getHeight() - AndroidUtilities.dp(28)};
            } else {
                p = new float[]{AndroidUtilities.displaySize.x - AndroidUtilities.dp(30), AndroidUtilities.dp(560)};
            }
            mark("sendForward");
            tap(p[0], p[1]);
        });
        // Чат 2 (Lena): сообщения приходят по очереди, на каждое — жест вниз (Copy, Save, Save, Add to Favorites).
        receiveAt(6.7, new ConceptsDemo.Item("Cabin address: 12 Pine Road, bring warm socks", null, null, null, 0));
        bottomAction(7.0, 0, 0.58f, past, reach);
        receiveAt(9.5, new ConceptsDemo.Item("First snow in the city tonight", "winter-street", null, null, 0));
        bottomAction(9.8, 1, 0.62f, past, reach);
        after(12.3, () -> {
            ArrayList<ConceptsDemo.Item> album = new ArrayList<>();
            album.add(new ConceptsDemo.Item("Weekend trip photos", null, "trip-aurora", null, 7001));
            album.add(new ConceptsDemo.Item("", "trip-cabin", null, null, 7001));
            album.add(new ConceptsDemo.Item("", "trip-sled", null, null, 7001));
            receive(account, album);
        });
        bottomAction(12.6, 2, 0.62f, past, reach);
        receiveAt(15.1, new ConceptsDemo.Item("", null, null, "snowman.tgs", 0));
        bottomAction(15.4, 3, 0.58f, past, reach);
        after(17.9, () -> Bulletin.hideVisible());
        after(18.1, () -> {
            ChatActivity chat = chat();
            View back = chat != null && chat.getActionBar() != null ? chat.getActionBar().getBackButton() : null;
            float[] p = back != null ? location(back, 0.5f, 0.5f) : new float[]{AndroidUtilities.dp(28), AndroidUtilities.dp(60)};
            mark("back");
            tap(p[0], p[1]);
        });
        after(0.15 + length + 0.4, () -> {
            finished = true;
            mark("end");
            writeLog();
            write(new File(dir(), "done"), "done");
        });
    }

    private static void receiveAt(double at, ConceptsDemo.Item item) {
        after(at, () -> {
            ArrayList<ConceptsDemo.Item> items = new ArrayList<>();
            items.add(item);
            receive(UserConfigAccount.get(), items);
        });
    }

    private static final class UserConfigAccount {
        static int get() {
            return org.telegram.messenger.UserConfig.selectedAccount;
        }
    }

    private static void receive(int account, ArrayList<ConceptsDemo.Item> items) {
        Bulletin.hideVisible(); // плашка прошлого действия уходит, когда приходит следующее сообщение
        ChatActivity chat = chat();
        if (chat != null) {
            chat.conceptsHideUndo();
        }
        ConceptsDemo.receive(account, ConceptsDemo.LENA, items, ids -> received.add(ids.isEmpty() ? 0 : ids.get(0)));
    }

    /** Нижнее действие — один непрерывный жест (как iOS `action`): обе оси по одной кривой, скорость плавно падает к отпусканию. */
    private static void bottomAction(double at, int index, float fx, float past, float reach) {
        after(at, () -> {
            if (received.size() <= index) {
                return;
            }
            float[] p = message(received.get(index), fx);
            if (p == null) {
                return;
            }
            gesture(p[0], p[1], 0.3, 1.5, time -> {
                double u = Math.max(0, Math.min(1, (time - 0.3) / 1.2));
                double angle = Math.PI / 2 * (1 - (1 - u) * (1 - u));
                double y = (1 - Math.cos(angle)) * reach;
                double x = 1.2 * Math.sin(angle) + 0.12 * (y / reach) * (y / reach);
                return new float[]{(float) (x * past), (float) y};
            }, 0.0);
        });
    }

    /** Мягкий старт, торможение медленнее разгона (как iOS cubic-bezier(0.3, 0, 0.1, 1)). */
    private static double ease(double u) {
        double x1 = 0.3, y1 = 0, x2 = 0.1, y2 = 1;
        double lo = 0, hi = 1;
        for (int i = 0; i < 30; i++) {
            double m = (lo + hi) / 2;
            double x = 3 * (1 - m) * (1 - m) * m * x1 + 3 * (1 - m) * m * m * x2 + m * m * m;
            if (x < u) lo = m; else hi = m;
        }
        double m = (lo + hi) / 2;
        return 3 * (1 - m) * (1 - m) * m * y1 + 3 * (1 - m) * m * m * y2 + m * m * m;
    }

    private static double track(double[][] keys, double t) {
        if (t <= keys[0][0]) return keys[0][1];
        for (int i = 1; i < keys.length; i++) {
            if (t <= keys[i][0]) {
                double[] a = keys[i - 1], b = keys[i];
                return a[1] + (b[1] - a[1]) * ease((t - a[0]) / Math.max(0.001, b[0] - a[0]));
            }
        }
        return keys[keys.length - 1][1];
    }

    /** Дуга большого пальца: X в долях `past`, Y в пикселях; парабола по Y, как в iOS. */
    private static float[] arc(double[][] xKeys, double[][] yKeys, double t, float past, float reach) {
        double y = track(yKeys, t);
        double yr = y / reach;
        double x = (track(xKeys, t) + 0.12 * yr * yr) * past;
        return new float[]{(float) x, (float) y};
    }
}
