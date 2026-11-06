package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ConceptsDemo;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Multi-action swipe on message bubbles. Swipe left to the reply threshold — a capsule of three slots grows around the
 * reply circle: up — Forward, center — Reply, down — an action by message type (Copy / Save to gallery / Add to Favorites /
 * Save to Downloads). Without lifting the finger, slide up or down and release on a slot. Moving the finger back past the
 * threshold collapses the capsule; releasing then cancels. Active only in the offline demo (`concepts_swipe_actions`);
 * otherwise the reply gesture is stock. The capsule is an overlay above the chat, so neighbouring cells never cover it.
 * The actions themselves are the stock context-menu options.
 */
@SuppressLint("ViewConstructor")
public class ConceptsSwipeActions extends View {

    enum Kind { FORWARD, REPLY, COPY, SAVE_MEDIA, FAVORITE_STICKER, SAVE_FILE }

    static final class Slot {
        final Kind kind;
        final String title;
        final Drawable icon;
        final boolean enabled;

        Slot(Context context, Kind kind, String title, int iconRes, boolean enabled) {
            this.kind = kind;
            this.title = title;
            this.icon = context.getResources().getDrawable(iconRes).mutate();
            this.enabled = enabled;
        }
    }

    // region точки входа из ChatActivity (по одной строке)

    private static ConceptsSwipeActions session;

    /** MOVE уже начатого свайпа ответа: `dx` — сдвиг пузыря (≤ 0), событие — в координатах списка. */
    public static void move(ChatActivity chat, View listView, View slidingView, MotionEvent e, int dx) {
        if (!ConceptsDemo.ENABLED || !ConceptsDemo.swipeActions || !(slidingView instanceof ChatMessageCell) || e == null) {
            return;
        }
        if (session == null || session.cell != slidingView || session.finished) {
            if (session != null && !session.finished) {
                session.finish(false);
            }
            ChatMessageCell cell = (ChatMessageCell) slidingView;
            if (cell.getMessageObject() == null || !(chat.getFragmentView() instanceof ViewGroup)) {
                return;
            }
            session = new ConceptsSwipeActions(chat, listView, cell);
        }
        session.update(dx, e.getY());
    }

    /** Отпускание пальца. true — жест обработан концептом (штатный ответ не выполнять). */
    public static boolean release(ChatActivity chat, MotionEvent e) {
        ConceptsSwipeActions s = session;
        if (s == null || s.finished) {
            return false;
        }
        boolean performed = e != null && e.getAction() == MotionEvent.ACTION_UP;
        Slot selected = s.finish(performed);
        if (selected == null) {
            return s.wasArmed; // отмена после порога; до порога — штатная логика
        }
        if (selected.kind == Kind.REPLY) {
            return false; // ответ — штатным кодом
        }
        if (!selected.enabled) {
            return true;
        }
        MessageObject message = s.cell.getMessageObject();
        MessageObject.GroupedMessages group = s.cell.getCurrentMessagesGroup();
        switch (selected.kind) {
            case FORWARD:
                chat.conceptsPerformOption(message, group, ChatActivity.OPTION_FORWARD);
                break;
            case COPY:
                chat.conceptsPerformOption(message, group, ChatActivity.OPTION_COPY);
                break;
            case SAVE_MEDIA:
                chat.conceptsPerformOption(message, group, ChatActivity.OPTION_SAVE_TO_GALLERY);
                renameMixedAlbumBulletin(group);
                break;
            case FAVORITE_STICKER:
                chat.conceptsPerformOption(message, group, ChatActivity.OPTION_ADD_STICKER_TO_FAVORITES);
                break;
            case SAVE_FILE:
                chat.conceptsPerformOption(message, group, ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC);
                break;
        }
        return true;
    }

    /** Смешанный альбом: штатное «3 items saved to gallery» → «1 video and 2 photos saved to gallery» (как в iOS-ролике). */
    private static void renameMixedAlbumBulletin(MessageObject.GroupedMessages group) {
        if (group == null || group.messages.size() < 2) {
            return;
        }
        int videos = 0, photos = 0;
        for (MessageObject m : group.messages) {
            if (m.isVideo()) {
                videos++;
            } else if (m.isPhoto()) {
                photos++;
            }
        }
        if (videos == 0 || photos == 0 || videos + photos != group.messages.size()) {
            return;
        }
        Bulletin bulletin = Bulletin.getVisibleBulletin();
        if (bulletin != null && bulletin.getLayout() instanceof Bulletin.LottieLayout) {
            String text = (videos == 1 ? "1 video" : videos + " videos") + " and " + (photos == 1 ? "1 photo" : photos + " photos") + " saved to gallery";
            ((Bulletin.LottieLayout) bulletin.getLayout()).textView.setText(text);
        }
    }

    /** Пока капсула на экране и пузырь не вернулся — штатный кружок ответа не рисуем (его роль у капсулы). */
    public static boolean hidesReplyButton(View slidingView) {
        ConceptsSwipeActions s = session;
        return s != null && s.cell == slidingView && s.hideStockButton;
    }

    // endregion

    private static final float ITEM = 38;          // dp, как iOS
    private static final float PADDING = 5;
    private static final float SPACING = 7;
    private static final float WIDTH = ITEM + PADDING * 2;
    private static final float THRESHOLD = 50;     // dp, порог ответа Telegram Android
    private static final float DEAD_ZONE = 14;
    private static final float HYSTERESIS = 6;
    private static final float PITCH = ITEM + SPACING;
    private static final float CIRCLE = 32;        // штатный кружок ответа Android

    private final ChatActivity chat;
    private final View listView;
    private final ChatMessageCell cell;
    private final ViewGroup host;
    private final Slot[] slots;
    private final int centerIndex;
    private final Theme.ResourcesProvider resources;

    private final Paint capsulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private final int[] hostLoc = new int[2];

    private boolean armed, wasArmed, finished, hideStockButton;
    private boolean capsuleShown, thumbRevealed;
    private float anchorY, sign;
    private int level, selectedOffset;
    private float lastCenterX, lastCenterY;
    private float pinnedX = Float.NaN, pinnedY = Float.NaN;

    // время событий анимаций (мс, uptime)
    private long showTime = -1, collapseTime = -1, thumbMoveTime = -1, pulseTime = -1, labelTime = -1;
    private float collapseDuration = 350, collapseFromWidth, collapseFromHeight, collapseFromSpread, collapseFromAlpha;
    private boolean collapseRemove;
    private float thumbFrom, thumbTo, thumbAlpha, thumbAlphaFrom, thumbAlphaTarget;
    private long thumbAlphaTime = -1;
    private int labelIndex = -1;
    private float labelAlpha;
    private long labelHideTime = -1;
    private int labelHideIndex = -1;

    private ConceptsSwipeActions(ChatActivity chat, View listView, ChatMessageCell cell) {
        super(chat.getParentActivity());
        this.chat = chat;
        this.listView = listView;
        this.cell = cell;
        this.resources = chat.getResourceProvider();
        this.host = (ViewGroup) chat.getFragmentView();
        this.slots = slotsFor(chat.getParentActivity(), cell);
        this.centerIndex = 1;
        setWillNotDraw(false);
        host.addView(this, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        int submenu = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resources);
        boolean dark = ColorUtils.calculateLuminance(submenu) < 0.5f;
        capsulePaint.setColor(submenu);
        capsulePaint.setShadowLayer(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(2), 0x26000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // тень капсулы
        int iconColor = Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resources);
        for (Slot slot : slots) {
            slot.icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        }
        // Выбранный слот — серая заливка, как подсветка пункта меню (цвет иконки, 10 %).
        thumbPaint.setColor(ColorUtils.setAlphaComponent(dark ? Color.WHITE : iconColor, 0x1f));
        labelPaint.setTextSize(AndroidUtilities.dp(13));
        labelPaint.setTypeface(AndroidUtilities.bold());
        labelPaint.setColor(Theme.getColor(Theme.key_chat_serviceText, resources));
    }

    private static Slot[] slotsFor(Context context, ChatMessageCell cell) {
        MessageObject m = cell.getMessageObject();
        MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
        boolean protectedContent = m.messageOwner != null && m.messageOwner.noforwards;
        Slot bottom;
        if (m.isSticker() || m.isAnimatedSticker()) {
            bottom = new Slot(context, Kind.FAVORITE_STICKER, LocaleController.getString(R.string.AddToFavorites), R.drawable.msg_fave, true);
        } else if (group != null || m.isPhoto() || m.isVideo() || m.isGif()) {
            // альбом — «Save 3 Items», как в iOS-ролике
            String label = group != null && group.messages.size() > 1 ? "Save " + group.messages.size() + " Items" : LocaleController.getString(R.string.SaveToGallery);
            bottom = new Slot(context, Kind.SAVE_MEDIA, label, R.drawable.msg_gallery, !protectedContent);
        } else if (m.isVoice() || m.isRoundVideo() || m.isPoll()) {
            bottom = new Slot(context, Kind.COPY, LocaleController.getString(R.string.Copy), R.drawable.msg_copy, false);
        } else if (m.isDocument() || m.isMusic()) {
            bottom = new Slot(context, Kind.SAVE_FILE, LocaleController.getString(R.string.SaveToDownloads), R.drawable.msg_download, !protectedContent);
        } else {
            bottom = new Slot(context, Kind.COPY, LocaleController.getString(R.string.Copy), R.drawable.msg_copy, !protectedContent);
        }
        return new Slot[]{
                new Slot(context, Kind.FORWARD, LocaleController.getString(R.string.Forward), R.drawable.msg_forward, !protectedContent),
                new Slot(context, Kind.REPLY, LocaleController.getString(R.string.Reply), R.drawable.menu_reply, true),
                bottom
        };
    }

    // region жест

    private static float dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private float boundary(int level) {
        return dp(DEAD_ZONE) + (level - 1) * dp(PITCH);
    }

    private void update(int dx, float y) {
        computeCenter();
        boolean reached = -dx >= dp(THRESHOLD);
        if (!armed && reached) {
            armed = true;
            wasArmed = true;
            anchorY = y;
            sign = 0;
            level = 0;
            selectedOffset = 0;
            setCapsuleShown(true);
            // «Ответить» выбран сразу: серый кружок под ним виден уже на пороге, как в iOS
            thumbRevealed = true;
            thumbFrom = thumbTo = 0;
            thumbMoveTime = -1;
            setThumbAlpha(1f);
        } else if (armed && -dx < dp(THRESHOLD) - dp(HYSTERESIS)) {
            armed = false;
            thumbRevealed = false;
            setSelected(0, false);
            haptic(HapticFeedbackConstants.KEYBOARD_TAP);
            setCapsuleShown(false);
        }
        if (armed) {
            float d = y - anchorY;
            float magnitude = Math.abs(d);
            float direction = d < 0 ? -1 : 1;
            if (sign != 0 && direction != sign) {
                level = 0;
            }
            int maxLevel = direction < 0 ? centerIndex : slots.length - 1 - centerIndex;
            int l = level;
            while (l < maxLevel && magnitude >= boundary(l + 1)) {
                l++;
            }
            while (l > 0 && magnitude < boundary(l) - dp(HYSTERESIS)) {
                l--;
            }
            level = l;
            sign = l == 0 ? 0 : direction;
            int offset = l == 0 ? 0 : (int) direction * l;
            if (offset != selectedOffset) {
                setSelected(offset, true);
            }
        }
        invalidate();
    }

    private void setSelected(int offset, boolean animated) {
        int previous = selectedOffset;
        if (offset != 0) {
            if (!thumbRevealed) {
                thumbRevealed = true;
                thumbFrom = previous;
                setThumbAlpha(1f);
            }
        }
        if (previous != offset) {
            thumbFrom = currentThumbOffset();
            thumbTo = offset;
            thumbMoveTime = animated ? now() : -1;
            pulseTime = animated && capsuleShown && !growing() ? now() : -1;
            if (armed) {
                showLabel(centerIndex + offset);
            }
        }
        selectedOffset = offset;
    }

    private void setThumbAlpha(float target) {
        thumbAlphaFrom = thumbAlpha;
        thumbAlphaTarget = target;
        thumbAlphaTime = now();
    }

    private void showLabel(int index) {
        if (labelIndex == index) {
            return;
        }
        if (labelIndex >= 0) {
            labelHideIndex = labelIndex;
            labelHideTime = now();
        }
        labelIndex = index;
        // Подписи ждут, пока капсула выедет (~0,18 с), как в iOS.
        long wait = showTime > 0 ? Math.max(0, showTime + 180 - now()) : 0;
        labelTime = now() + wait;
        AndroidUtilities.runOnUIThread(() -> {
            if (!finished && labelIndex == index && armed) {
                haptic(-1);
            }
        }, wait);
    }

    private void hideLabel() {
        if (labelIndex >= 0) {
            labelHideIndex = labelIndex;
            labelHideTime = now();
        }
        labelIndex = -1;
    }

    private void setCapsuleShown(boolean shown) {
        if (shown == capsuleShown) {
            return;
        }
        capsuleShown = shown;
        if (shown) {
            pinnedX = lastCenterX;
            pinnedY = lastCenterY;
            showTime = now();
            collapseTime = -1;
            hideStockButton = true;
            thumbAlpha = 0;
            thumbAlphaTarget = 0;
            thumbFrom = thumbTo = 0;
            // «Ответить» выбран сразу на пороге: подпись видна без движения (серый кружок включает update()).
            showLabel(centerIndex);
        } else {
            startCollapse(350, false);
            hideLabel();
            AndroidUtilities.runOnUIThread(() -> {
                if (!capsuleShown && !finished) {
                    hideStockButton = false;
                    pinnedX = pinnedY = Float.NaN;
                }
            }, 200);
        }
    }

    private void startCollapse(float duration, boolean remove) {
        float[] size = capsuleSize(now());
        collapseFromWidth = size[0];
        collapseFromHeight = size[1];
        collapseFromSpread = spread(now());
        collapseFromAlpha = capsuleAlpha(now());
        collapseTime = now();
        collapseDuration = duration;
        collapseRemove = remove;
        setThumbAlpha(0f);
    }

    /** Отпускание: выбранный слот или null (отмена). */
    private Slot finish(boolean performed) {
        finished = true;
        Slot selected = performed && armed ? slots[centerIndex + selectedOffset] : null;
        if (selected != null && !selected.enabled) {
            haptic(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
        } else if (selected != null && selected.kind != Kind.REPLY) {
            haptic(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.KEYBOARD_TAP);
        }
        if (capsuleShown) {
            capsuleShown = false;
            startCollapse(300, true);
            hideLabel();
        } else {
            collapseRemove = true;
            if (collapseTime < 0) {
                remove();
                return selected;
            }
        }
        AndroidUtilities.runOnUIThread(this::remove, 420);
        invalidate();
        return selected;
    }

    private void remove() {
        if (getParent() != null) {
            ((ViewGroup) getParent()).removeView(this);
        }
        if (session == this) {
            session = null;
        }
    }

    private void haptic(int constant) {
        try {
            if (constant < 0) {
                AndroidUtilities.vibrateCursor(listView);
            } else {
                listView.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } catch (Exception ignore) {
        }
    }

    // endregion

    // region анимации (время → значение; рисуется каждый кадр, пока что-то движется)

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private boolean growing() {
        return showTime > 0 && collapseTime < 0 && now() - showTime < 1000;
    }

    /** Пружина роста капсулы как в iOS: ширина — без перелёта, высота (и разлёт иконок) — с отскоком (ζ = 0,55). */
    private float[] growProgress(long time) {
        if (showTime < 0) {
            return new float[]{0, 0};
        }
        double t = (time - showTime) / 1000.0;
        if (t >= 1.0) {
            return new float[]{1, 1};
        }
        double omega = Math.sqrt(200.0);
        double zeta = 0.55;
        double wd = omega * Math.sqrt(1 - zeta * zeta);
        double q = 1 - Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + zeta * omega / wd * Math.sin(wd * t));
        double p = 1 - (1 + omega * t) * Math.exp(-omega * t);
        return new float[]{(float) p, (float) q};
    }

    private float collapseT(long time) {
        if (collapseTime < 0) {
            return -1;
        }
        return Math.min(1f, (time - collapseTime) / collapseDuration);
    }

    private float pulse(long time) {
        if (pulseTime < 0) {
            return 0;
        }
        double t = (time - pulseTime) / 1000.0;
        if (t >= 0.6) {
            return 0;
        }
        return (float) (0.11 * Math.exp(-7.0 * t) * Math.sin(12.2 * t));
    }

    private float fullHeight() {
        return dp(ITEM) * slots.length + dp(SPACING) * (slots.length - 1) + dp(PADDING) * 2;
    }

    private float[] capsuleSize(long time) {
        float ct = collapseT(time);
        if (ct >= 0) {
            float q = Math.min(1f, ct / 0.45f);
            float flight = collapseRemove ? 1f - 0.45f * ct : 1f;
            float w = (collapseFromWidth + (dp(CIRCLE) - collapseFromWidth) * q) * flight;
            float h = (collapseFromHeight + (dp(CIRCLE) - collapseFromHeight) * q) * flight;
            return new float[]{w, h};
        }
        float[] g = growProgress(time);
        float w = dp(CIRCLE) + (dp(WIDTH) - dp(CIRCLE)) * g[0];
        float h = dp(CIRCLE) + (fullHeight() - dp(CIRCLE)) * g[1];
        return new float[]{w, h * (1 + pulse(time))};
    }

    private float spread(long time) {
        float ct = collapseT(time);
        if (ct >= 0) {
            return collapseFromSpread * (1f - Math.min(1f, ct / 0.45f));
        }
        return growProgress(time)[1];
    }

    private float capsuleAlpha(long time) {
        float ct = collapseT(time);
        if (ct >= 0) {
            float fade = Math.max(0f, 1f - ct / 0.6f);
            return collapseFromAlpha * fade * fade;
        }
        if (showTime < 0) {
            return 0;
        }
        return Math.min(1f, (time - showTime) / 180f);
    }

    private float currentThumbOffset() {
        if (thumbMoveTime < 0) {
            return thumbTo;
        }
        float t = Math.min(1f, (now() - thumbMoveTime) / 300f);
        return thumbFrom + (thumbTo - thumbFrom) * userEase(t);
    }

    /** Базовый изинг пользователя cubic-bezier(0, 0.4, 0, 1). */
    private static final CubicBezierInterpolator USER_EASE = new CubicBezierInterpolator(0, 0.4, 0, 1);

    private static float userEase(float t) {
        return USER_EASE.getInterpolation(t);
    }

    // endregion

    // region отрисовка

    /** Центр штатного кружка ответа в координатах этого слоя (как в ChatActivity.drawReplyButton). */
    private boolean computeCenter() {
        if (cell.getParent() == null) {
            return false;
        }
        MessageObject m = cell.getMessageObject();
        float tx = cell.getSlidingOffsetX();
        float x = listView.getMeasuredWidth() + tx * (m != null && m.isOut() ? 0.5f : 1f);
        float y = cell.getTop() + cell.getMeasuredHeight() / 2f;
        listView.getLocationInWindow(loc);
        getLocationInWindow(hostLoc);
        lastCenterX = x + loc[0] - hostLoc[0];
        lastCenterY = y + loc[1] - hostLoc[1];
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long time = now();
        computeCenter();
        boolean collapsing = collapseTime >= 0;
        float cx = collapsing && collapseRemove || Float.isNaN(pinnedX) ? lastCenterX : pinnedX;
        float cy = collapsing && collapseRemove || Float.isNaN(pinnedY) ? lastCenterY : pinnedY;
        if (!collapsing && Float.isNaN(pinnedX)) {
            return;
        }

        float alpha = capsuleAlpha(time);
        float[] size = capsuleSize(time);
        float spread = spread(time);
        float pulse = collapsing ? 0 : pulse(time);
        boolean animating = false;

        // Серая подложка штатного кружка: гаснет 0,3 с, пока растёт капсула.
        if (!collapsing && showTime > 0 && time - showTime < 300) {
            float pa = 1f - (time - showTime) / 300f;
            Paint service = chat.getThemedPaint(Theme.key_paint_chatActionBackground);
            Theme.applyServiceShaderMatrixForView(this, host, resources);
            int was = service.getAlpha();
            service.setAlpha((int) (was * 0.6f * pa));
            canvas.drawCircle(cx, cy, dp(CIRCLE) / 2f, service);
            service.setAlpha(was);
            animating = true;
        }

        if (alpha > 0.005f) {
            rect.set(cx - size[0] / 2f, cy - size[1] / 2f, cx + size[0] / 2f, cy + size[1] / 2f);
            int wasAlpha = capsulePaint.getAlpha();
            capsulePaint.setAlpha((int) (255 * alpha));
            canvas.drawRoundRect(rect, size[0] / 2f, size[0] / 2f, capsulePaint);
            capsulePaint.setAlpha(wasAlpha);

            // ползунок выбора
            float thumbA = thumbAlphaTime < 0 ? thumbAlphaTarget : thumbAlphaFrom + (thumbAlphaTarget - thumbAlphaFrom) * Math.min(1f, (time - thumbAlphaTime) / (thumbAlphaTarget > 0 ? 300f : collapseDuration * 0.4f));
            thumbAlpha = thumbA;
            if (thumbA > 0.005f) {
                float off = currentThumbOffset();
                float ty = cy + off * dp(PITCH) * spread * (1 + pulse);
                int was = thumbPaint.getAlpha();
                thumbPaint.setAlpha((int) (was * thumbA * Math.min(1f, alpha * 1.5f)));
                canvas.drawCircle(cx, ty, dp(ITEM) / 2f, thumbPaint);
                thumbPaint.setAlpha(was);
            }

            // иконки: проявляются по очереди от центра; при схлопывании съезжаются в центр и гаснут
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                float iy = cy + (i - centerIndex) * dp(PITCH) * spread * (1 + pulse);
                float ia;
                float scale = 1f;
                if (collapsing) {
                    float ct = collapseT(time);
                    ia = Math.max(0f, 1f - ct / 0.5f);
                } else {
                    long delay = i == centerIndex ? 0 : (long) (40 + Math.abs(i - centerIndex) * 30);
                    float t = Math.max(0f, Math.min(1f, (time - showTime - delay) / 250f));
                    ia = t;
                    scale = 0.8f + 0.2f * userEase(t);
                }
                if (!slot.enabled) {
                    ia *= 0.4f;
                }
                if (ia <= 0.005f) {
                    continue;
                }
                Drawable d = slot.icon;
                int s = (int) (dp(24) * scale);
                if (slot.kind == Kind.REPLY) {
                    s = (int) (dp(24) * scale);
                }
                d.setBounds((int) (cx - s / 2f), (int) (iy - s / 2f), (int) (cx + s / 2f), (int) (iy + s / 2f));
                d.setAlpha((int) (255 * ia * Math.min(1f, alpha * 1.5f)));
                d.draw(canvas);
            }
        }

        // подписи слева от капсулы: плашка цвета сервисных сообщений (как дата), белый текст; появление — баунс масштаба
        if (labelHideIndex >= 0 && labelHideTime > 0) {
            float t = Math.min(1f, (time - labelHideTime) / 120f);
            if (t < 1f) {
                drawLabel(canvas, labelHideIndex, cx, cy, spread, pulse, 1f - t, 1f);
                animating = true;
            } else {
                labelHideIndex = -1;
            }
        }
        if (labelIndex >= 0 && !collapsing && time >= labelTime) {
            float t = (time - labelTime) / 1000f;
            float la = Math.min(1f, t / 0.12f);
            // пружина 0,85 → 1 (stiffness 500, damping 36 → ζ ≈ 0,8)
            double omega = Math.sqrt(500.0);
            double zeta = 36.0 / (2 * omega);
            double wd = omega * Math.sqrt(1 - zeta * zeta);
            double x = Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + zeta * omega / wd * Math.sin(wd * t));
            float scale = (float) (1 - 0.15 * x);
            drawLabel(canvas, labelIndex, cx, cy, spread, pulse, la, scale);
            if (t < 0.6f) {
                animating = true;
            }
        } else if (labelIndex >= 0 && !collapsing) {
            animating = true;
        }

        if (collapsing) {
            if (collapseT(time) < 1f) {
                animating = true;
            } else if (!collapseRemove) {
                collapseTime = -1;
                showTime = -1;
            }
        } else if (growing() || pulseTime > 0 && time - pulseTime < 600 || thumbMoveTime > 0 && time - thumbMoveTime < 300 || thumbAlphaTime > 0 && time - thumbAlphaTime < 400) {
            animating = true;
        }
        if (animating || armed) {
            invalidate();
        }
    }

    private void drawLabel(Canvas canvas, int index, float cx, float cy, float spread, float pulse, float alpha, float scale) {
        Slot slot = slots[index];
        float iy = cy + (index - centerIndex) * dp(PITCH) * spread * (1 + pulse);
        float textWidth = labelPaint.measureText(slot.title);
        float h = dp(24);
        float w = textWidth + dp(20);
        float right = cx - dp(WIDTH) / 2f - dp(8);
        float lx = right - w / 2f;
        canvas.save();
        canvas.scale(scale, scale, lx, iy);
        rect.set(right - w, iy - h / 2f, right, iy + h / 2f);
        Paint service = chat.getThemedPaint(Theme.key_paint_chatActionBackground);
        Theme.applyServiceShaderMatrixForView(this, host, resources);
        int was = service.getAlpha();
        service.setAlpha((int) (was * alpha));
        canvas.drawRoundRect(rect, h / 2f, h / 2f, service);
        service.setAlpha(was);
        if (chat.themeDelegate != null && chat.themeDelegate.hasGradientService()) {
            Paint darken = Theme.chat_actionBackgroundGradientDarkenPaint;
            int wasD = darken.getAlpha();
            darken.setAlpha((int) (wasD * alpha));
            canvas.drawRoundRect(rect, h / 2f, h / 2f, darken);
            darken.setAlpha(wasD);
        }
        int wasT = labelPaint.getAlpha();
        labelPaint.setAlpha((int) (255 * alpha));
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        canvas.drawText(slot.title, right - w + dp(10), iy - (fm.ascent + fm.descent) / 2f, labelPaint);
        labelPaint.setAlpha(wasT);
        canvas.restore();
    }

    // endregion
}
