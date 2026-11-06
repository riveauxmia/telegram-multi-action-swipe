import Foundation
import UIKit
import Display
import AsyncDisplayKit
import SwiftSignalKit
import Postbox
import TelegramCore
import AccountContext
import UndoUI
import ChatControllerInteraction
import ChatMessageItemView
import ChatMessageItem
import ChatMessageSwipeToReplyNode
import ChatMessageBubbleItemNode
import TelegramUIPreferences
import TelegramPresentationData

// Multi-action swipe concept: swipe left on a bubble, pass the reply threshold, keep the finger down and slide
// up or down to pick an action (Forward / Copy / Save / Add to Favorites / Save to Files).
// Enabled by the `--concepts-swipe-actions` launch argument; without it the reply gesture is untouched.
// Demo boundary: Forward opens the real chat picker, Copy writes to the pasteboard, Save / Favorites only show
// the confirmation toast (saving to Photos or the sticker list is a system / server action and is not done here).

enum ConceptsSwipeActions {
    private static var context: AccountContext?
    /// Turns the multi-action swipe on. Call once from a chat controller; without the launch argument nothing changes.
    static func enableIfNeeded(context: AccountContext) {
        guard CommandLine.arguments.contains("--concepts-swipe-actions"), self.context == nil else {
            return
        }
        self.context = context
        ChatSwipeActionsPrototype.isEnabled = true
        ChatSwipeActionsPrototype.perform = { kind, message, interaction in
            self.perform(kind, message: message, interaction: interaction)
        }
    }

    private static func perform(_ kind: ChatSwipeActionKind, message: Message, interaction: ChatControllerInteraction) {
        guard let context = self.context else {
            return
        }
        let presentationData = context.sharedContext.currentPresentationData.with { $0 }
        switch kind {
        case .reply:
            break
        case .forward:
            if let chat = interaction.navigationController()?.topViewController as? ChatControllerImpl {
                chat.interfaceInteraction?.forwardMessages([message])
            }
        case .copy:
            interaction.copyText(message.text)
        case .saveMedia:
            let isVideo = message.media.contains(where: { ($0 as? TelegramMediaFile)?.isVideo == true })
            let count = ChatSwipeActionsPrototype.groupCount
            interaction.displayUndo(.mediaSaved(text: count > 1 ? (isVideo ? "1 video and \(count - 1) photos saved" : "\(count) photos saved") : (isVideo ? presentationData.strings.Gallery_VideoSaved : presentationData.strings.Gallery_ImageSaved)))
        case .favoriteSticker:
            // Плашка сверху: снизу она закрывала бы сам стикер, а он стоит у нижнего края чата.
            if let chat = interaction.navigationController()?.topViewController as? ChatControllerImpl {
                chat.present(UndoOverlayController(presentationData: presentationData, content: .succeed(text: "Sticker added to Favorites", timeout: 2.5, customUndoText: nil), elevatedLayout: false, position: .top, animateInAsReplacement: false, action: { _ in return false }), in: .current)
            } else {
                interaction.displayUndo(.succeed(text: "Sticker added to Favorites", timeout: 2.5, customUndoText: nil))
            }
        case .saveFile:
            interaction.displayUndo(.succeed(text: "Saved to Files", timeout: 2.5, customUndoText: nil))
        }
    }
}
