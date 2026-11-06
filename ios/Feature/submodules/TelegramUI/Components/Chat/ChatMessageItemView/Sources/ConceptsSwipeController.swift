import Foundation
import UIKit
import AsyncDisplayKit
import Display
import TelegramCore
import TelegramPresentationData
import ChatMessageItem
import ChatMessageSwipeToReplyNode

// Multi-action swipe concept: all gesture logic lives outside the message nodes. The nodes (bubble, sticker) only call
// began / changed / ended from `swipeToReplyGesture`; while `ChatSwipeActionsPrototype.isEnabled` is false
// (`--concepts-swipe-actions` launch argument) the controller does nothing.

public final class ConceptsSwipeController {
    private var session: ChatSwipeActionsSession?

    public init() {
    }

    private func setListScrollLocked(node: ASDisplayNode, _ locked: Bool) {
        var current: ASDisplayNode? = node.supernode
        while let candidate = current {
            if let listView = candidate as? ListView {
                listView.scrollEnabled = !locked
                break
            }
            current = candidate.supernode
        }
    }

    public func began(node: ASDisplayNode) {
        guard ChatSwipeActionsPrototype.isEnabled else {
            return
        }
        self.setListScrollLocked(node: node, true)
    }

    /// Вызывается на каждом `.changed`, когда кружок ответа уже стоит на месте (`centerNode.position`).
    public func changed(node: ASDisplayNode, item: ChatMessageItem, centerNode: ChatMessageSwipeToReplyNode, translation: CGPoint, threshold: CGFloat) {
        guard ChatSwipeActionsPrototype.isEnabled else {
            return
        }
        if self.session == nil {
            let theme = item.presentationData.theme
            var count = 1
            if case let .group(messages) = item.content {
                count = messages.count
            }
            ChatSwipeActionsPrototype.groupCount = count
            self.session = ChatSwipeActionsSession(
                slots: ChatSwipeActionsPrototype.slots(message: item.message, strings: item.presentationData.strings, count: count),
                centerNode: centerNode,
                parent: node,
                labelFill: selectDateFillStaticColor(theme: theme.theme, wallpaper: theme.wallpaper),
                enableBlur: item.controllerInteraction.enableFullTranslucency,
                foregroundColor: theme.theme.contextMenu.primaryColor,
                capsuleColor: theme.theme.rootController.navigationBar.segmentedBackgroundColor,
                isDark: theme.theme.overallDarkAppearance,
                wallpaper: item.controllerInteraction.presentationContext.backgroundNode,
                makeIcon: {
                    return ChatMessageSwipeToReplyNode(fillColor: selectDateFillStaticColor(theme: theme.theme, wallpaper: theme.wallpaper), enableBlur: item.controllerInteraction.enableFullTranslucency && dateFillNeedsBlur(theme: theme.theme, wallpaper: theme.wallpaper), foregroundColor: bubbleVariableColor(variableColor: theme.theme.chat.message.shareButtonForegroundColor, wallpaper: theme.wallpaper), backgroundNode: nil, action: .reply)
                }
            )
        }
        self.session?.update(translation: translation, threshold: threshold, centerPosition: centerNode.position)
    }

    /// Конец жеста. nil — рейки не было (функция выключена): решает штатный код. Иначе true — ответить, false — не отвечать.
    public func ended(node: ASDisplayNode, item: ChatMessageItem?, isEnded: Bool) -> Bool? {
        guard ChatSwipeActionsPrototype.isEnabled else {
            return nil
        }
        self.setListScrollLocked(node: node, false)
        guard let session = self.session else {
            return nil
        }
        self.session = nil
        let slot = session.finish(performed: isEnded)
        if let slot, slot.kind != .reply, slot.isEnabled, let item {
            ChatSwipeActionsPrototype.perform?(slot.kind, item.message, item.controllerInteraction)
        }
        return slot?.kind == .reply
    }
}

public extension ChatSwipeActionsPrototype {
    /// Слоты сверху вниз: Переслать · Ответить · действие по типу сообщения.
    static func slots(message: EngineRawMessage, strings: PresentationStrings, count: Int = 1) -> [ChatSwipeActionSlot] {
        func icon(_ outline: String, _ filled: String) -> String {
            return ChatSwipeActionsPrototype.filledIcons ? "sf:" + filled : outline
        }
        var forwardEnabled = !message.isCopyProtected() && !message.containsSecretMedia
        if message.media.contains(where: { $0 is TelegramMediaAction || $0 is TelegramMediaExpiredContent }) {
            forwardEnabled = false
        }
        let saveEnabled = !message.isCopyProtected()
        var bottom = ChatSwipeActionSlot(kind: .copy, title: strings.Conversation_ContextMenuCopy, iconName: icon("Chat/Context Menu/Copy", "doc.on.doc.fill"), isEnabled: false)
        var mediaSlot: ChatSwipeActionSlot?
        for media in message.media {
            if let file = media as? TelegramMediaFile {
                if file.isSticker {
                    mediaSlot = ChatSwipeActionSlot(kind: .favoriteSticker, title: strings.Stickers_AddToFavorites, iconName: icon("Chat/Context Menu/Fave", "star.fill"), isEnabled: true)
                } else if file.isVoice || file.isInstantVideo {
                    break
                } else if file.isVideo || file.isAnimated {
                    // Альбом (несколько медиа) сохраняется целиком, а не «как GIF» по первому элементу.
                    let title = count > 1 ? "Save \(count) Items" : (file.isAnimated ? strings.Preview_SaveGif : strings.Gallery_SaveVideo)
                    mediaSlot = ChatSwipeActionSlot(kind: .saveMedia, title: title, iconName: icon("Chat/Context Menu/Save", "square.and.arrow.down.fill"), isEnabled: saveEnabled)
                } else {
                    mediaSlot = ChatSwipeActionSlot(kind: .saveFile, title: strings.Conversation_SaveToFiles, iconName: icon("Chat/Context Menu/Save", "square.and.arrow.down.fill"), isEnabled: saveEnabled)
                }
                break
            } else if media is TelegramMediaImage {
                mediaSlot = ChatSwipeActionSlot(kind: .saveMedia, title: count > 1 ? "Save \(count) Photos" : strings.Gallery_SaveImage, iconName: icon("Chat/Context Menu/Save", "square.and.arrow.down.fill"), isEnabled: saveEnabled)
                break
            }
        }
        if let mediaSlot {
            bottom = mediaSlot
        } else if !message.text.isEmpty {
            bottom = ChatSwipeActionSlot(kind: .copy, title: strings.Conversation_ContextMenuCopy, iconName: icon("Chat/Context Menu/Copy", "doc.on.doc.fill"), isEnabled: !message.isCopyProtected())
        }
        return [
            ChatSwipeActionSlot(kind: .forward, title: strings.Conversation_ContextMenuForward, iconName: icon("Chat/Context Menu/Forward", "arrowshape.turn.up.right.fill"), isEnabled: forwardEnabled),
            ChatSwipeActionSlot(kind: .reply, title: strings.Conversation_ContextMenuReply, iconName: icon("Chat/Context Menu/Reply", "arrowshape.turn.up.left.fill"), isEnabled: true),
            bottom
        ]
    }
}
