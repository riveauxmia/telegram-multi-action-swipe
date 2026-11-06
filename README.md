# Multi-Action Swipe — more than "Reply" on a message swipe

Reply is not the only action you reach for all the time. Swipe a message to the left as usual — and
instead of stopping at **Reply**, keep the finger down and slide **up** or **down** to pick another action:

* **↑ Forward**
* **← Reply** (the usual swipe, in the center)
* **↓ Copy** for text · **Save** for photos, videos and albums ("Save 3 Items") · **Add to Favorites** for stickers · **Save to Files** for files

One gesture, no menus. Move the finger back past the reply threshold and the slots collapse — releasing then cancels.
On iOS, voice messages, round videos and polls have no secondary action: the bottom slot is dimmed.

Telegram concept for **iOS** (UIKit / AsyncDisplayKit) and **Android** (Java, Telegram-Android), built on top of the official sources.

| iOS | Android |
|---|---|
| [Media/ios.mp4](Media/ios.mp4) | [Media/android.mp4](Media/android.mp4) |

## Repository

| Path | What |
|---|---|
| `ios/Feature/submodules/…` | New iOS files, at the same paths as in Telegram-iOS — copy the folder over a checkout. |
| `ios/Integration/telegram-ios.patch` | Hooks in existing Telegram-iOS files (`git apply`). |
| `android/Feature/TMessagesProj/…` | New Android files and demo assets, at the same paths as in Telegram-Android. |
| `android/Integration/telegram-android.patch` | Hooks in existing Telegram-Android files (`git apply`). |
| `Media/` | The two recordings of the concept (1920×1920, 60 fps). |

## iOS

| File | What |
|---|---|
| `…/ChatMessageSwipeToReplyNode/Sources/ConceptsSwipeRail.swift` | The rail of three slots, selection by vertical drag (dead zone, step, hysteresis), haptics, 300 ms animations, state per slot (enabled / dimmed). |
| `…/ChatMessageItemView/Sources/ConceptsSwipeController.swift` | Gesture controller used by the message nodes (`began` / `changed` / `ended`) and the choice of slots by message type. |
| `TelegramUI/Sources/ConceptsSwipeActions.swift` | Turns the feature on and performs the chosen action (Forward, Copy, Save, Add to Favorites, Save to Files). |

Hooks in existing files (see the patch): `ChatMessageBubbleItemNode` and `ChatMessageAnimatedStickerItemNode`
(a controller property and three calls inside `swipeToReplyGesture`), `ChatMessageSwipeToReplyNode` (the reply circle
can be cloned into an overlay, changes its arrow color and morphs its outline), `ChatController.swift`
(one line in `viewDidAppear`), and the `BUILD` files of the two touched components.

Base commit: `fb0e62ba8a147f0821da6aa63c09da7d79a10111` (TelegramMessenger/Telegram-iOS).

```sh
cd Telegram-iOS
git checkout fb0e62ba8a147f0821da6aa63c09da7d79a10111
cp -R /path/to/this-repo/ios/Feature/submodules/ submodules/
git apply /path/to/this-repo/ios/Integration/telegram-ios.patch
```

Build as usual (`build-system/Make/Make.py` or the generated Xcode project) and launch with the argument
`--concepts-swipe-actions`. Without it nothing changes: every new branch is behind
`ChatSwipeActionsPrototype.isEnabled`, which stays `false`.

## Android

| File | What |
|---|---|
| `org/telegram/ui/ConceptsSwipeActions.java` | The capsule of three slots drawn above the chat, slot selection, haptics, and the actions (they call the stock context-menu options). |
| `org/telegram/messenger/ConceptsDemo.java` | Offline demo: fictional account and chats answered inside the app, no sign-in, no network. |
| `org/telegram/ui/ConceptsSwipeAutopilot.java` | Scripted run of the whole flow with real touch events, used for the recording. |
| `src/main/assets/concepts/` | Demo images, a short clip and the snowman theme assets. |

Hooks in existing files (see the patch): `ChatActivity` (three lines in the swipe-to-reply handler and two
small helpers), `LaunchActivity` (two lines), `ConnectionsManager` (demo answers instead of network requests),
`AndroidUtilities` (one line, the "copied" toast).

Base commit: `3de37c8cfb039452be7398909461da6288babf15` (DrKLO/Telegram).

```sh
cd Telegram
git checkout 3de37c8cfb039452be7398909461da6288babf15
cp -R /path/to/this-repo/android/Feature/TMessagesProj/ TMessagesProj/
git apply /path/to/this-repo/android/Integration/telegram-android.patch
```

Build as usual (Gradle). **Note:** `ConceptsDemo.ENABLED` is `true`, so this build is an offline demo — it never
talks to Telegram servers and the swipe is switched on by the intent extra below. Setting it to `false` gives
regular Telegram back, but the multi-action swipe is part of the demo and is switched off with it.

```sh
adb shell am start -n org.telegram.messenger/org.telegram.ui.LaunchActivity \
  --ez concepts_swipe_actions true
# scripted run from the video:
#   --es concepts_swipe_autopilot all
```

## How it works

* **Threshold.** The first part is the stock swipe-to-reply: rubber band, haptic on the threshold, spring return.
* **Slots.** After the threshold a capsule grows around the reply circle: Forward above, Reply in the middle, a
  type-dependent action below. Vertical movement is counted from the point where the threshold was crossed; a small
  dead zone keeps a plain reply a plain reply, and the slot changes with a light haptic.
* **Cancel.** Finger back to the left of the threshold — the slots collapse; releasing does nothing.
* **Lists.** The chat list does not scroll while the gesture is active.

## Notes and limits

* This is a prototype on fictional data (Sofia, Lena, …). Nothing is sent anywhere.
* Forward opens the real chat picker; Copy writes to the pasteboard. Save and Add to Favorites only show the
  confirmation toast in the iOS build — saving to Photos and editing the sticker list are system / server actions.
* Static stickers and round videos are not handled on iOS (their nodes have their own gesture).
* The offline-demo harness and the recording autopilot for iOS belong to a larger private test bench and are not
  included; the Android demo is self-contained.
* Demo media: stock clips from Mixkit (Mixkit License); avatars and photos are demo images.
* Code comments are partly in Russian.
* Telegram sources are GPLv2; these files are meant to be used on top of them.
