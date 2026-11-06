package org.telegram.messenger;

import android.content.Intent;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

/**
 * Offline demo: a fictional account and chats with no sign-in and no network. Instead of a server, requests are answered
 * inside the app (`handleRequest`): history, dialogs, profiles, chat theme, forwarding. Any other request stays
 * unanswered, as if there were no connection. Data: a chat with Sofia Reyes, a chat with Lena Moretti, the Forward list.
 * Demo time is today 9:41 plus the time since launch (like the status bar).
 */
public class ConceptsDemo {

    /** The offline demo is on for the whole build. Set to false to get regular Telegram networking back. */
    public static final boolean ENABLED = true;

    /** Launch flags (intent extras): concepts_swipe_actions, concepts_swipe_autopilot, concepts_open_chat. */
    public static boolean swipeActions;
    public static String autopilot;
    public static String openChat = "sofia";
    /** Высота окна приложения в px (0 — весь экран): в мокапе ролика низ экрана обрезан, как iOS `--concepts-demo-compact`. */
    public static int compactPx;

    public static final long SELF = 7770001L;
    public static final long SOFIA = 7770010L;
    public static final long NOAH = 7770011L;
    public static final long LENA = 7770012L;
    public static final long BEN = 7770013L;
    public static final long OMAR = 7770014L;
    public static final long CREW = 7771010L;
    public static final long DESIGN = 7771011L;
    public static final long IDEAS = 7771012L;
    public static final long DEV = 7771013L;
    public static final long NEIGHBORS = 7771014L;
    public static final long OFFICE = 7771015L;

    private static final long launchElapsed = SystemClock.elapsedRealtime();
    private static final long anchorMs;

    static {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 41);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        anchorMs = c.getTimeInMillis();
    }

    public static long nowMillis() {
        return anchorMs + (SystemClock.elapsedRealtime() - launchElapsed);
    }

    public static int now() {
        return (int) (nowMillis() / 1000);
    }

    // region запуск

    /** LaunchActivity.onCreate, до проверки входа: флаги и вымышленный аккаунт. */
    public static void onLaunch(int account, Intent intent) {
        if (!ENABLED) {
            return;
        }
        if (SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_HIGH) {
            // эмулятор считается слабым: стикеры ждут кэш кадров и появляются с задержкой; ролик показывает Pixel 10 — флагман
            SharedConfig.overrideDevicePerformanceClass(SharedConfig.PERFORMANCE_CLASS_HIGH);
            LiteMode.loadPreference();
        }
        if (intent != null) {
            if (intent.hasExtra("concepts_swipe_actions")) {
                swipeActions = intent.getBooleanExtra("concepts_swipe_actions", false);
            }
            if (intent.hasExtra("concepts_swipe_autopilot")) {
                autopilot = intent.getStringExtra("concepts_swipe_autopilot");
            }
            if (intent.hasExtra("concepts_compact_px")) {
                compactPx = intent.getIntExtra("concepts_compact_px", 0);
            }
            if (intent.hasExtra("concepts_open_chat")) {
                openChat = intent.getStringExtra("concepts_open_chat");
            }
        }
        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated()) {
            TLRPC.User self = user(SELF, "Alex", "Rivera", null);
            self.self = true;
            self.phone = "15550100";
            self.flags |= 16;
            ConnectionsManager.getInstance(account).setUserId(self.id);
            config.setCurrentUser(self);
            config.saveConfig(true);
            ArrayList<TLRPC.User> users = new ArrayList<>();
            users.add(self);
            MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
            MessagesController.getInstance(account).putUser(self, false);
        }
        Utilities.globalQueue.postRunnable(() -> data(account));
        // Тема ⛄ закреплена за чатами заранее: чат открывается сразу в ней, без мелькания темы по умолчанию.
        for (long peer : new long[]{SOFIA, LENA}) {
            TLRPC.TL_chatTheme theme = new TLRPC.TL_chatTheme();
            theme.emoticon = "⛄";
            ChatThemeController.getInstance(account).setDialogTheme(peer, theme, false);
        }
    }

    /** После того как LaunchActivity собрала стек: сразу открыть нужный чат (как iOS `--concepts-demo-open-chat`). */
    public static void onLaunchStack(LaunchActivity activity, int account) {
        if (ENABLED && compactPx > 0) {
            // Короткое окно (как iOS --concepts-demo-compact): окно остаётся во весь экран — иначе система не красит
            // значки статус-бара под тему (белые на белом), — а укорачивается корень интерфейса; нижний отступ навбара убираем.
            android.view.ViewGroup content = activity.findViewById(android.R.id.content);
            View root = content.getChildAt(0);
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) root.getLayoutParams();
            lp.height = compactPx;
            lp.gravity = android.view.Gravity.TOP;
            root.setLayoutParams(lp);
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    return new android.view.WindowInsets.Builder(insets)
                        .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.NONE)
                        .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(bars.left, bars.top, bars.right, 0))
                        .build();
                }
                return insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), 0);
            });
            content.requestApplyInsets();
        }
        if (!ENABLED || openChat == null || openChat.isEmpty() || "none".equals(openChat)) {
            return;
        }
        long peer = "lena".equals(openChat) ? LENA : SOFIA;
        AndroidUtilities.runOnUIThread(() -> {
            Bundle args = new Bundle();
            args.putLong("user_id", peer);
            activity.presentFragment(new ChatActivity(args), false, true);
            if (autopilot != null && !autopilot.isEmpty()) {
                org.telegram.ui.ConceptsSwipeAutopilot.start(account, autopilot);
            }
        }, 400);
    }

    // endregion

    // region данные

    private static class Data {
        final HashMap<Long, TLRPC.User> users = new HashMap<>();
        final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
        /** Диалог → сообщения по возрастанию id. */
        final HashMap<Long, ArrayList<TLRPC.Message>> history = new HashMap<>();
        final ArrayList<Long> dialogOrder = new ArrayList<>();
        TLRPC.TL_theme theme;
    }

    private static Data data;
    private static int nextMessageId = 1;
    private static int pts = 1;

    private static synchronized Data data(int account) {
        if (data != null) {
            return data;
        }
        Data d = new Data();
        int now = now();
        TLRPC.User sofia = user(SOFIA, "Sofia", "Reyes", "p-sofia");
        sofia.premium = true;
        TLRPC.User lena = user(LENA, "Lena", "Moretti", "p-lena");
        TLRPC.User noah = user(NOAH, "Noah", "Kim", "p-noah");
        TLRPC.User ben = user(BEN, "Ben", "Ortiz", "p-ben");
        TLRPC.User omar = user(OMAR, "Omar", "Haddad", "p-omar");
        for (TLRPC.User u : new TLRPC.User[]{sofia, lena}) {
            TLRPC.TL_userStatusOnline status = new TLRPC.TL_userStatusOnline();
            status.expires = now + 365 * 86400;
            u.status = status;
            u.flags |= 64;
        }
        for (TLRPC.User u : new TLRPC.User[]{sofia, lena, noah, ben, omar}) {
            d.users.put(u.id, u);
        }
        TLRPC.Chat crew = group(CREW, "Weekend Crew", "p-crew");
        TLRPC.Chat design = group(DESIGN, "Design Crew", "p-design");
        TLRPC.Chat ideas = group(IDEAS, "Ideas Lab", "p-ideas");
        TLRPC.Chat dev = group(DEV, "Dev Chat", "p-dev");
        TLRPC.Chat neighbors = group(NEIGHBORS, "Neighbors", "p-neighbors");
        TLRPC.Chat office = group(OFFICE, "Office News", "p-office");
        for (TLRPC.Chat c : new TLRPC.Chat[]{crew, design, ideas, dev, neighbors, office}) {
            d.chats.put(c.id, c);
        }

        // Чат 1 — Sofia (как в iOS): сообщение 3 — центр кадра и первый жест.
        addHistory(d, SOFIA, 0, now, new Object[][]{
                {120, "Morning! Any plans for the weekend?", true},
                {110, "Thinking about a weekend in the mountains", false},
                {80, "Nice! Is it quiet enough to work?", true},
                {75, "Looks great! Skating at 7?", false},
                {70, "Sure, I'll bring the hot tea", true},
                {65, "Perfect, see you at the rink", false},
                {60, "Don't forget warm socks", true},
        });
        // Чат 2 — Lena: сюда пересылают сообщение из чата 1, сюда же приходят новые сообщения.
        addHistory(d, LENA, 0, now, new Object[][]{
                {10, "Morning! Did you see the snow?", true},
                {5, "Yes! The whole street is white", false},
        });
        addHistory(d, -CREW, NOAH, now, new Object[][]{{20, "Who's bringing the sled?", true}});
        addHistory(d, NOAH, 0, now, new Object[][]{{30, "Sent the invoice, thanks!", true}});
        addHistory(d, -DESIGN, LENA, now, new Object[][]{{55, "New moodboard is in the folder", true}});
        addHistory(d, -IDEAS, BEN, now, new Object[][]{{75, "What if the swipe had three actions?", true}});
        addHistory(d, -DEV, OMAR, now, new Object[][]{{110, "Build is green again", true}});
        addHistory(d, BEN, 0, now, new Object[][]{{140, "Can you review my PR?", false}});
        addHistory(d, -NEIGHBORS, NOAH, now, new Object[][]{{170, "Anyone has a ladder?", true}});
        addHistory(d, OMAR, 0, now, new Object[][]{{210, "Ok, talk tomorrow", true}});
        addHistory(d, -OFFICE, BEN, now, new Object[][]{{260, "Kitchen is closed for cleaning today", true}});

        d.dialogOrder.addAll(d.history.keySet());
        d.dialogOrder.sort((a, b) -> Integer.compare(last(d, b).date, last(d, a).date));
        d.theme = snowmanTheme(account);
        data = d;
        return d;
    }

    private static void addHistory(Data d, long dialogId, long author, int now, Object[][] items) {
        ArrayList<TLRPC.Message> list = new ArrayList<>();
        for (Object[] item : items) {
            boolean incoming = (Boolean) item[2];
            long from = incoming ? (dialogId < 0 ? author : dialogId) : SELF;
            list.add(message(nextMessageId++, dialogId, from, !incoming, now - (Integer) item[0] * 60, (String) item[1], null));
        }
        d.history.put(dialogId, list);
    }

    private static TLRPC.Message last(Data d, long dialogId) {
        ArrayList<TLRPC.Message> list = d.history.get(dialogId);
        return list.get(list.size() - 1);
    }

    private static TLRPC.Peer peer(long dialogId) {
        if (dialogId < 0) {
            TLRPC.TL_peerChat p = new TLRPC.TL_peerChat();
            p.chat_id = -dialogId;
            return p;
        }
        TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
        p.user_id = dialogId;
        return p;
    }

    private static TLRPC.TL_message message(int id, long dialogId, long from, boolean out, int date, String text, TLRPC.MessageMedia media) {
        TLRPC.TL_message m = new TLRPC.TL_message();
        m.id = id;
        m.out = out;
        m.date = date;
        m.message = text;
        m.peer_id = peer(dialogId);
        m.from_id = peer(from);
        m.flags |= 256;
        m.dialog_id = dialogId;
        if (media != null) {
            m.media = media;
            m.flags |= 512;
        } else {
            m.media = new TLRPC.TL_messageMediaEmpty();
        }
        return m;
    }

    private static TLRPC.User user(long id, String first, String last, String avatar) {
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = id;
        u.access_hash = id * 31;
        u.first_name = first;
        u.last_name = last;
        u.flags |= 1 | 2 | 4;
        u.contact = true;
        u.mutual_contact = true;
        if (avatar != null) {
            TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
            photo.photo_id = 90_000_000L + id;
            photo.dc_id = 2;
            photo.photo_small = avatarLocation(photo.photo_id, 'a');
            photo.photo_big = avatarLocation(photo.photo_id, 'c');
            u.photo = photo;
            u.flags |= 32;
            pendingAvatars.put(photo.photo_id, avatar);
        } else {
            u.photo = new TLRPC.TL_userProfilePhotoEmpty();
        }
        return u;
    }

    private static TLRPC.Chat group(long id, String title, String avatar) {
        TLRPC.TL_chat c = new TLRPC.TL_chat();
        c.id = id;
        c.title = title;
        c.participants_count = 5;
        c.version = 1;
        c.date = now() - 30 * 86400;
        TLRPC.TL_chatPhoto photo = new TLRPC.TL_chatPhoto();
        photo.photo_id = 91_000_000L + id;
        photo.dc_id = 2;
        photo.photo_small = avatarLocation(photo.photo_id, 'a');
        photo.photo_big = avatarLocation(photo.photo_id, 'c');
        c.photo = photo;
        pendingAvatars.put(photo.photo_id, avatar);
        return c;
    }

    private static TLRPC.TL_fileLocationToBeDeprecated avatarLocation(long photoId, char local) {
        TLRPC.TL_fileLocationToBeDeprecated l = new TLRPC.TL_fileLocationToBeDeprecated();
        l.volume_id = -photoId;
        l.local_id = local;
        l.dc_id = 2;
        return l;
    }

    // endregion

    // region файлы (ресурсы APK → пути кэша FileLoader, где приложение ищет скачанные файлы)

    private static final HashMap<Long, String> pendingAvatars = new HashMap<>();
    private static boolean avatarsPlaced;

    private static byte[] asset(String name) {
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("concepts/" + name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void write(File file, byte[] bytes) {
        if (file == null || bytes == null || file.exists() && file.length() == bytes.length) {
            return;
        }
        try {
            File dir = file.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Кладёт файл по обоим путям, где FileLoader ищет готовый файл (кэш и постоянная папка). */
    private static void place(int account, TLObject attach, String ext, byte[] bytes) {
        FileLoader loader = FileLoader.getInstance(account);
        write(loader.getPathToAttach(attach, ext, true), bytes);
        write(loader.getPathToAttach(attach, ext, false), bytes);
    }

    private static synchronized void placeAvatars(int account) {
        if (avatarsPlaced) {
            return;
        }
        avatarsPlaced = true;
        for (HashMap.Entry<Long, String> e : pendingAvatars.entrySet()) {
            byte[] bytes = asset(e.getValue() + ".jpg");
            place(account, avatarLocation(e.getKey(), 'a'), "jpg", bytes);
            place(account, avatarLocation(e.getKey(), 'c'), "jpg", bytes);
        }
    }

    private static long fileIds = 95_000_000L;

    private static synchronized TLRPC.Photo photo(int account, String name) {
        byte[] bytes = asset(name + ".jpg");
        if (bytes == null) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = ++fileIds;
        photo.access_hash = photo.id * 7;
        photo.file_reference = new byte[0];
        photo.date = now();
        photo.dc_id = 2;
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "y";
        size.w = o.outWidth;
        size.h = o.outHeight;
        size.size = bytes.length;
        TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
        location.volume_id = photo.id;
        location.local_id = 1;
        location.dc_id = 2;
        size.location = location;
        photo.sizes.add(size);
        // маленькое превью — пока основной файл декодируется, пузырь не пустой
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 40, Math.max(1, 40 * o.outHeight / o.outWidth), true);
            java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, stream);
            TLRPC.TL_photoCachedSize cached = new TLRPC.TL_photoCachedSize();
            cached.type = "s";
            cached.w = small.getWidth();
            cached.h = small.getHeight();
            cached.bytes = stream.toByteArray();
            TLRPC.TL_fileLocationToBeDeprecated l2 = new TLRPC.TL_fileLocationToBeDeprecated();
            l2.volume_id = photo.id;
            l2.local_id = 2;
            l2.dc_id = 2;
            cached.location = l2;
            photo.sizes.add(0, cached);
            bitmap.recycle();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        place(account, size, "jpg", bytes);
        return photo;
    }

    private static final HashMap<String, TLRPC.Document> stickers = new HashMap<>();

    /** Стикер из ресурсов демо: один документ на файл (его заранее прогревает автопилот — Lottie рисуется без задержки). */
    public static synchronized TLRPC.Document sticker(int account, String asset) {
        TLRPC.Document cached = stickers.get(asset);
        if (cached != null) {
            return cached;
        }
        ArrayList<TLRPC.DocumentAttribute> attrs = new ArrayList<>();
        TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
        sticker.alt = "☃️";
        TLRPC.TL_inputStickerSetShortName set = new TLRPC.TL_inputStickerSetShortName();
        set.short_name = "AnimatedEmojies";
        sticker.stickerset = set; // анимированный стикер только с набором (MessageObject.isAnimatedStickerDocument)
        attrs.add(sticker);
        TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
        size.w = 512;
        size.h = 512;
        attrs.add(size);
        TLRPC.Document doc = document(account, asset, "application/x-tgsticker", "AnimatedSticker.tgs", "tgs", attrs);
        if (doc != null) {
            // превью обязательно (иначе не стикер); файл превью не нужен — рисует Lottie
            TLRPC.TL_photoSize thumb = new TLRPC.TL_photoSize();
            thumb.type = "s";
            thumb.w = 128;
            thumb.h = 128;
            TLRPC.TL_fileLocationToBeDeprecated l = new TLRPC.TL_fileLocationToBeDeprecated();
            l.volume_id = doc.id;
            l.local_id = 3;
            l.dc_id = 2;
            thumb.location = l;
            doc.thumbs.add(thumb);
            doc.flags |= 1;
            stickers.put(asset, doc);
        }
        return doc;
    }

    private static synchronized TLRPC.Document document(int account, String asset, String mime, String fileName, String ext, ArrayList<TLRPC.DocumentAttribute> attributes) {
        byte[] bytes = asset(asset);
        if (bytes == null) {
            return null;
        }
        if ("tgv".equals(ext)) {
            // Узор обоев: при скачивании Telegram сохраняет его распакованным (SvgHelper читает SVG напрямую).
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = ++fileIds;
        doc.access_hash = doc.id * 7;
        doc.file_reference = new byte[0];
        doc.date = now();
        doc.mime_type = mime;
        doc.size = bytes.length;
        doc.dc_id = 2;
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = fileName;
        doc.attributes.add(name);
        if (attributes != null) {
            doc.attributes.addAll(attributes);
        }
        place(account, doc, ext, bytes);
        return doc;
    }

    private static TLRPC.TL_theme snowmanTheme(int account) {
        // Настоящая тема ⛄ из кэша ConceptsServerAssets (manifest.json, светлый вариант), паттерн — её .tgv.
        ArrayList<TLRPC.DocumentAttribute> attrs = new ArrayList<>();
        TLRPC.TL_documentAttributeImageSize imageSize = new TLRPC.TL_documentAttributeImageSize();
        imageSize.w = 1125;
        imageSize.h = 2436;
        attrs.add(imageSize);
        TLRPC.Document pattern = document(account, "pattern-snowman.tgv", "application/x-tgwallpattern", "pattern.tgv", "tgv", attrs);
        TLRPC.TL_wallPaper wallPaper = new TLRPC.TL_wallPaper();
        wallPaper.id = 5782630687571969871L;
        wallPaper.access_hash = 1;
        wallPaper.slug = "MIo6r0qGSFAFAAAAtL8TsDzNX60";
        wallPaper.pattern = true;
        wallPaper.document = pattern;
        TLRPC.TL_wallPaperSettings settings = new TLRPC.TL_wallPaperSettings();
        settings.background_color = 0x81a9f0;
        settings.second_background_color = 0x9ed3f4;
        settings.third_background_color = 0xaeded2;
        settings.fourth_background_color = 0xc0c4ec;
        settings.intensity = 61;
        settings.rotation = 180;
        settings.flags = 1 | 8 | 16 | 32 | 64;
        wallPaper.settings = settings;
        wallPaper.flags |= 4;

        TLRPC.TL_themeSettings light = new TLRPC.TL_themeSettings();
        light.base_theme = new TLRPC.TL_baseThemeClassic();
        light.accent_color = 0xff3b8fbb;
        light.outbox_accent_color = 0xff3b8fbb;
        light.message_colors.add(0xfff4fffc);
        light.message_colors.add(0xffd8f5ff);
        light.message_colors.add(0xffffffff);
        light.message_colors.add(0xffd2fffb);
        light.wallpaper = wallPaper;
        light.flags = 1 | 2 | 8;

        TLRPC.TL_themeSettings dark = new TLRPC.TL_themeSettings();
        dark.base_theme = new TLRPC.TL_baseThemeNight();
        dark.accent_color = 0xff4bb7ef;
        dark.message_colors.add(0xff4bb4be);
        dark.message_colors.add(0xff336eb5);
        dark.message_colors.add(0xff3d9cab);
        dark.message_colors_animated = true;
        TLRPC.TL_wallPaper darkWallPaper = new TLRPC.TL_wallPaper();
        darkWallPaper.id = wallPaper.id;
        darkWallPaper.access_hash = 1;
        darkWallPaper.slug = wallPaper.slug;
        darkWallPaper.pattern = true;
        darkWallPaper.dark = true;
        darkWallPaper.document = pattern;
        TLRPC.TL_wallPaperSettings darkSettings = new TLRPC.TL_wallPaperSettings();
        darkSettings.background_color = 0x2e688a;
        darkSettings.second_background_color = 0x4b3260;
        darkSettings.third_background_color = 0x131f26;
        darkSettings.fourth_background_color = 0x164538;
        darkSettings.intensity = -92;
        darkSettings.rotation = 180;
        darkSettings.flags = 1 | 8 | 16 | 32 | 64;
        darkWallPaper.settings = darkSettings;
        darkWallPaper.flags |= 4;
        dark.wallpaper = darkWallPaper;
        dark.flags = 1 | 2;

        TLRPC.TL_theme theme = new TLRPC.TL_theme();
        theme.id = 5431456822668034262L;
        theme.access_hash = 1;
        theme.slug = "snowman-without-snow";
        theme.title = "⛄️ Chat Theme";
        theme.for_chat = true;
        theme.emoticon = "⛄";
        theme.settings.add(light);
        theme.settings.add(dark);
        theme.flags = 8 | 64;
        return theme;
    }

    // endregion

    // region «сервер»

    /** ConnectionsManager.sendRequestInternal: в демо запросы не уходят в сеть. Возвращает true — запрос обработан здесь. */
    public static boolean handleRequest(int account, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        if (!ENABLED) {
            return false;
        }
        TLObject response;
        try {
            response = respond(account, request);
        } catch (Throwable e) {
            FileLog.e(e);
            response = null;
        }
        android.util.Log.d("ConceptsDemo", request.getClass().getSimpleName() + (response != null ? " -> " + response.getClass().getSimpleName() : " -> (нет ответа)"));
        if (response == null) {
            return true; // как без сети: ответа нет
        }
        final TLObject result = response;
        Utilities.stageQueue.postRunnable(() -> {
            if (onComplete != null) {
                onComplete.run(result, null);
            } else if (onCompleteTimestamp != null) {
                onCompleteTimestamp.run(result, null, nowMillis());
            } else if (result instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) result, false);
            }
        }, 30);
        return true;
    }

    private static TLObject respond(int account, TLObject request) {
        Data d = data(account);
        placeAvatars(account);
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self != null) {
            synchronized (ConceptsDemo.class) {
                d.users.put(SELF, self);
            }
        }
        if (request instanceof TLRPC.TL_messages_getDialogs) {
            TLRPC.TL_messages_getDialogs req = (TLRPC.TL_messages_getDialogs) request;
            TLRPC.TL_messages_dialogs res = new TLRPC.TL_messages_dialogs();
            if (req.offset_date == 0 && req.offset_id == 0 && req.folder_id == 0) {
                synchronized (ConceptsDemo.class) {
                    for (long dialogId : d.dialogOrder) {
                        res.dialogs.add(dialog(d, dialogId));
                        res.messages.add(last(d, dialogId));
                    }
                }
            }
            res.users.addAll(d.users.values());
            res.chats.addAll(d.chats.values());
            return res;
        } else if (request instanceof TLRPC.TL_messages_getPinnedDialogs) {
            TLRPC.TL_messages_peerDialogs res = new TLRPC.TL_messages_peerDialogs();
            res.state = state();
            return res;
        } else if (request instanceof TLRPC.TL_messages_getPeerDialogs) {
            TLRPC.TL_messages_getPeerDialogs req = (TLRPC.TL_messages_getPeerDialogs) request;
            TLRPC.TL_messages_peerDialogs res = new TLRPC.TL_messages_peerDialogs();
            synchronized (ConceptsDemo.class) {
                for (TLRPC.InputDialogPeer p : req.peers) {
                    if (p instanceof TLRPC.TL_inputDialogPeer) {
                        long id = dialogId(((TLRPC.TL_inputDialogPeer) p).peer);
                        if (d.history.containsKey(id)) {
                            res.dialogs.add(dialog(d, id));
                            res.messages.add(last(d, id));
                        }
                    }
                }
            }
            res.users.addAll(d.users.values());
            res.chats.addAll(d.chats.values());
            res.state = state();
            return res;
        } else if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory req = (TLRPC.TL_messages_getHistory) request;
            long id = dialogId(req.peer);
            TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
            synchronized (ConceptsDemo.class) {
                ArrayList<TLRPC.Message> list = d.history.get(id);
                if (list != null) {
                    for (int i = list.size() - 1; i >= 0; i--) {
                        TLRPC.Message m = list.get(i);
                        boolean take;
                        if (req.offset_id == 0) {
                            take = true;
                        } else if (req.add_offset < 0) {
                            take = true; // загрузка «вокруг» — вся короткая история
                        } else {
                            take = m.id < req.offset_id;
                        }
                        if (take) {
                            res.messages.add(m);
                        }
                    }
                }
            }
            res.users.addAll(d.users.values());
            res.chats.addAll(d.chats.values());
            return res;
        } else if (request instanceof TLRPC.TL_users_getFullUser) {
            TLRPC.TL_users_getFullUser req = (TLRPC.TL_users_getFullUser) request;
            long id = req.id instanceof TLRPC.TL_inputUserSelf ? SELF : req.id.user_id;
            TLRPC.TL_userFull full = new TLRPC.TL_userFull();
            full.id = id;
            full.settings = new TLRPC.TL_peerSettings();
            full.notify_settings = new TLRPC.TL_peerNotifySettings();
            full.voice_messages_forbidden = false;
            if (id == SOFIA || id == LENA) {
                TLRPC.TL_chatTheme theme = new TLRPC.TL_chatTheme();
                theme.emoticon = "⛄";
                full.theme = theme;
            }
            TLRPC.TL_users_userFull res = new TLRPC.TL_users_userFull();
            res.full_user = full;
            res.users.addAll(d.users.values());
            return res;
        } else if (request instanceof TL_account.getChatThemes) {
            TL_account.TL_themes res = new TL_account.TL_themes();
            res.hash = 7;
            res.themes.add(d.theme);
            return res;
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            return forward(account, d, (TLRPC.TL_messages_forwardMessages) request);
        } else if (request instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
            synchronized (ConceptsDemo.class) {
                res.pts = pts;
            }
            res.pts_count = 0;
            return res;
        }
        return null;
    }

    private static TLRPC.TL_updates_state state() {
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = pts;
        state.date = now();
        state.seq = 1;
        return state;
    }

    private static long dialogId(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            return -peer.chat_id;
        } else if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return SELF;
        }
        return peer.user_id;
    }

    private static TLRPC.TL_dialog dialog(Data d, long dialogId) {
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.peer = peer(dialogId);
        int top = last(d, dialogId).id;
        dialog.top_message = top;
        dialog.read_inbox_max_id = top;
        dialog.read_outbox_max_id = top;
        dialog.unread_count = 0;
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
        return dialog;
    }

    private static TLObject forward(int account, Data d, TLRPC.TL_messages_forwardMessages req) {
        long to = dialogId(req.to_peer);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = now();
        synchronized (ConceptsDemo.class) {
            for (int i = 0; i < req.id.size(); i++) {
                TLRPC.Message source = null;
                for (ArrayList<TLRPC.Message> list : d.history.values()) {
                    for (TLRPC.Message m : list) {
                        if (m.id == req.id.get(i)) {
                            source = m;
                        }
                    }
                }
                if (source == null) {
                    continue;
                }
                TLRPC.TL_message m = message(nextMessageId++, to, SELF, true, now(), source.message, source.media instanceof TLRPC.TL_messageMediaEmpty ? null : source.media);
                if (!req.drop_author) {
                    TLRPC.TL_messageFwdHeader fwd = new TLRPC.TL_messageFwdHeader();
                    fwd.from_id = source.from_id;
                    fwd.date = source.date;
                    fwd.flags |= 1;
                    m.fwd_from = fwd;
                    m.flags |= 4;
                }
                ArrayList<TLRPC.Message> list = d.history.get(to);
                if (list == null) {
                    list = new ArrayList<>();
                    d.history.put(to, list);
                }
                list.add(m);
                TL_update.TL_updateMessageID idUpdate = new TL_update.TL_updateMessageID();
                idUpdate.id = m.id;
                idUpdate.random_id = req.random_id.get(i);
                updates.updates.add(idUpdate);
                TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
                newMessage.message = m;
                newMessage.pts = ++pts;
                newMessage.pts_count = 1;
                updates.updates.add(newMessage);
            }
        }
        updates.users.addAll(d.users.values());
        updates.chats.addAll(d.chats.values());
        return updates;
    }

    // endregion

    // region входящие по ходу ролика

    /** Содержимое входящего сообщения: текст и/или медиа. */
    public static class Item {
        public final String text;
        public final String image;
        public final String video;
        public final String sticker;
        public final long group;

        public Item(String text, String image, String video, String sticker, long group) {
            this.text = text;
            this.image = image;
            this.video = video;
            this.sticker = sticker;
            this.group = group;
        }
    }

    /** Собеседник присылает сообщения (как iOS ConceptsOfflineDemo.receiveMessages). Готовые id — в onDone (UI-поток). */
    public static void receive(int account, long from, ArrayList<Item> items, Utilities.Callback<ArrayList<Integer>> onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            Data d = data(account);
            ArrayList<TLRPC.Update> updates = new ArrayList<>();
            ArrayList<Integer> ids = new ArrayList<>();
            synchronized (ConceptsDemo.class) {
                for (Item item : items) {
                    TLRPC.MessageMedia media = null;
                    if (item.image != null) {
                        TLRPC.TL_messageMediaPhoto mp = new TLRPC.TL_messageMediaPhoto();
                        mp.photo = photo(account, item.image);
                        mp.flags |= 1;
                        media = mp;
                    } else if (item.video != null) {
                        ArrayList<TLRPC.DocumentAttribute> attrs = new ArrayList<>();
                        TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
                        video.w = 1280;
                        video.h = 720;
                        video.duration = 3;
                        video.supports_streaming = true;
                        attrs.add(video);
                        TLRPC.Document doc = document(account, item.video + ".mp4", "video/mp4", item.video + ".mp4", "mp4", attrs);
                        TLRPC.Photo cover = photo(account, item.video);
                        if (doc != null && cover != null) {
                            for (TLRPC.PhotoSize size : cover.sizes) {
                                doc.thumbs.add(size);
                            }
                            doc.flags |= 1;
                        }
                        TLRPC.TL_messageMediaDocument md = new TLRPC.TL_messageMediaDocument();
                        md.document = doc;
                        md.video = true;
                        md.flags |= 1;
                        media = md;
                    } else if (item.sticker != null) {
                        TLRPC.TL_messageMediaDocument md = new TLRPC.TL_messageMediaDocument();
                        md.document = sticker(account, item.sticker);
                        md.flags |= 1;
                        media = md;
                    }
                    TLRPC.TL_message m = message(nextMessageId++, from, from, false, now(), item.text == null ? "" : item.text, media);
                    if (item.group != 0) {
                        m.grouped_id = item.group;
                        m.flags |= 131072;
                    }
                    ArrayList<TLRPC.Message> list = d.history.get(from);
                    list.add(m);
                    ids.add(m.id);
                    TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();
                    update.message = m;
                    update.pts = ++pts;
                    update.pts_count = 1;
                    updates.add(update);
                }
            }
            ArrayList<TLRPC.User> users = new ArrayList<>(d.users.values());
            ArrayList<TLRPC.Chat> chats = new ArrayList<>(d.chats.values());
            Utilities.stageQueue.postRunnable(() -> {
                MessagesController.getInstance(account).processUpdateArray(updates, users, chats, false, 0);
                AndroidUtilities.runOnUIThread(() -> onDone.run(ids));
            });
        });
    }

    // endregion
}
