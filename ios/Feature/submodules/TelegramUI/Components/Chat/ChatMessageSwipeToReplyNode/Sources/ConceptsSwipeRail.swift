import Foundation
import UIKit
import Display
import AsyncDisplayKit
import AppBundle
import WallpaperBackgroundNode
import SwiftSignalKit
import TelegramCore
import ChatControllerInteraction
import ComponentFlow
import GlassBackgroundComponent

// Multi-action swipe concept. Active only with the `--concepts-swipe-actions` launch argument
// (ConceptsSwipeActions.swift in TelegramUI); otherwise `isEnabled == false` and the reply gesture behaves exactly
// like in Telegram. A rail of three slots: up — Forward, center — Reply, down — by message type
// (Copy / Save / Add to Favorites). Reactions stay on double tap.

public enum ChatSwipeActionKind {
    case forward
    case reply
    case copy
    case saveMedia
    case favoriteSticker
    case saveFile
}

public struct ChatSwipeActionSlot {
    public let kind: ChatSwipeActionKind
    public let title: String
    public let iconName: String
    public let isEnabled: Bool

    public init(kind: ChatSwipeActionKind, title: String, iconName: String, isEnabled: Bool) {
        self.kind = kind
        self.title = title
        self.iconName = iconName
        self.isEnabled = isEnabled
    }
}

public enum ChatSwipeActionsPrototype {
    /// Вариант с залитыми иконками для сравнения (`--concepts-swipe-filled`).
    public static let filledIcons = CommandLine.arguments.contains("--concepts-swipe-filled")
    public static var isEnabled = false
    /// Исполнитель выбранного действия (ответ выполняет сам пузырь). Ставится в TelegramUI.
    /// Сколько сообщений в группе (сетка медиа) под пальцем: «Save» относится ко всем.
    public static var groupCount: Int = 1
    public static var perform: ((ChatSwipeActionKind, EngineRawMessage, ChatControllerInteraction) -> Void)?
}

private let itemSize: CGFloat = 38.0
/// Базовый изинг пользователя cubic-bezier(0, 0.4, 0, 1).
private func swipeUserEasing(_ x: Double) -> Double {
    let (x1, y1, x2, y2) = (0.0, 0.4, 0.0, 1.0)
    func sample(_ t: Double, _ a: Double, _ b: Double) -> Double {
        let u = 1.0 - t
        return 3.0 * u * u * t * a + 3.0 * u * t * t * b + t * t * t
    }
    var lo = 0.0, hi = 1.0
    for _ in 0..<24 {
        let mid = (lo + hi) / 2.0
        if sample(mid, x1, x2) < x { lo = mid } else { hi = mid }
    }
    return sample((lo + hi) / 2.0, y1, y2)
}

private let capsulePadding: CGFloat = 5.0
private let itemSpacing: CGFloat = 7.0
private let capsuleWidth: CGFloat = itemSize + capsulePadding * 2.0
private let selectedScale: CGFloat = 1.08
private let baseCurve = ContainedViewLayoutTransitionCurve.custom(0.0, 0.4, 0.0, 1.0)

private func swipeTransition(_ animated: Bool = true) -> ContainedViewLayoutTransition {
    return animated ? .animated(duration: 0.3, curve: baseCurve) : .immediate
}

/// Капсула из QuickShareScreen (лонгтап по «Переслать» у поста канала): размытие + тень, иконки-кружки внутри.
private func generateBubbleShadowImage(shadow: UIColor, diameter: CGFloat, shadowBlur: CGFloat) -> UIImage? {
    return generateImage(CGSize(width: diameter + shadowBlur * 2.0, height: diameter + shadowBlur * 2.0), rotatedContext: { size, context in
        context.clear(CGRect(origin: CGPoint(), size: size))
        context.setFillColor(shadow.cgColor)
        context.setShadow(offset: CGSize(), blur: shadowBlur, color: shadow.cgColor)
        context.fillEllipse(in: CGRect(origin: CGPoint(x: shadowBlur, y: shadowBlur), size: CGSize(width: diameter, height: diameter)))
        context.setShadow(offset: CGSize(), blur: 1.0, color: shadow.cgColor)
        context.fillEllipse(in: CGRect(origin: CGPoint(x: shadowBlur, y: shadowBlur), size: CGSize(width: diameter, height: diameter)))
        context.setFillColor(UIColor.clear.cgColor)
        context.setBlendMode(.copy)
        context.fillEllipse(in: CGRect(origin: CGPoint(x: shadowBlur, y: shadowBlur), size: CGSize(width: diameter, height: diameter)))
    })?.stretchableImage(withLeftCapWidth: Int(shadowBlur + diameter / 2.0), topCapHeight: Int(shadowBlur + diameter / 2.0))
}

private final class ChatSwipeActionSlotNode: ASDisplayNode {
    let slot: ChatSwipeActionSlot
    private let selectionNode: ASDisplayNode
    private let foregroundColor: UIColor
    private let iconNode: ASImageNode
    let labelNode: ASDisplayNode
    private let labelBackground: NavigationBackgroundNode
    private var labelBubble: WallpaperBubbleBackgroundNode?
    private let labelText: ImmediateTextNode

    init(slot: ChatSwipeActionSlot, labelFill: UIColor, enableBlur: Bool, foregroundColor: UIColor, wallpaper: WallpaperBackgroundNode?) {
        self.slot = slot
        self.foregroundColor = foregroundColor
        // Выбранный слот — в сером кружке (серый = тот же цвет иконки с малой непрозрачностью).
        self.selectionNode = ASDisplayNode()
        self.selectionNode.backgroundColor = foregroundColor.withAlphaComponent(0.14)
        self.selectionNode.alpha = 0.0
        self.selectionNode.isUserInteractionEnabled = false
        self.iconNode = ASImageNode()
        self.iconNode.isUserInteractionEnabled = false
        self.labelNode = ASDisplayNode()
        self.labelNode.alpha = 0.0
        self.labelNode.isUserInteractionEnabled = false
        self.labelBackground = NavigationBackgroundNode(color: labelFill, enableBlur: enableBlur)
        // Как у плашки даты: на обоях с узором подложка — тот же слой обоев (свой режим наложения), а не плоский цвет.
        if let wallpaper, wallpaper.hasExtraBubbleBackground(), let bubble = wallpaper.makeBubbleBackground(for: .free) {
            bubble.clipsToBounds = true
            self.labelBubble = bubble
        }
        self.labelText = ImmediateTextNode()

        super.init()

        let size = CGSize(width: itemSize, height: itemSize)
        // Иконки — контурные ассеты Telegram (как в контекстном меню), увеличены до 26 pt; имя с префиксом `sf:` — SF Symbol.
        let loaded: UIImage?
        if slot.iconName.hasPrefix("sf:") {
            loaded = UIImage(systemName: String(slot.iconName.dropFirst(3)), withConfiguration: UIImage.SymbolConfiguration(pointSize: 40.0, weight: .semibold))
        } else {
            loaded = UIImage(bundleImageName: slot.iconName)
        }
        if let icon = loaded {
            let glyph: CGFloat = slot.iconName.hasPrefix("sf:") ? 21.0 : 26.0   // залитые символы массивнее контурных: тот же визуальный размер
            let iconSize = CGSize(width: glyph, height: glyph)
            self.iconNode.image = generateImage(size, rotatedContext: { size, context in
                context.clear(CGRect(origin: .zero, size: size))
                let fitted = icon.size.width > 0.0 ? min(iconSize.width / icon.size.width, iconSize.height / icon.size.height) : 1.0
                let imageSize = CGSize(width: icon.size.width * fitted, height: icon.size.height * fitted)
                let imageRect = CGRect(origin: CGPoint(x: floor((size.width - imageSize.width) / 2.0), y: floor((size.height - imageSize.height) / 2.0)), size: imageSize)
                context.translateBy(x: imageRect.midX, y: imageRect.midY)
                context.scaleBy(x: 1.0, y: -1.0)
                context.translateBy(x: -imageRect.midX, y: -imageRect.midY)
                context.setFillColor(foregroundColor.cgColor)
                context.clip(to: imageRect, mask: icon.cgImage!)
                context.fill(imageRect)
            })
        }
        self.addSubnode(self.selectionNode)
        self.addSubnode(self.iconNode)
        // Иконку «Ответить» рисует копия штатного кружка сверху (со своей анимацией).
        self.iconNode.isHidden = slot.kind == .reply
        self.selectionNode.frame = CGRect(origin: .zero, size: size)
        self.selectionNode.cornerRadius = size.height / 2.0
        self.iconNode.frame = CGRect(origin: .zero, size: size)

        // Подпись — стеклянная пилюля, как имя над выбранным аватаром в QuickShare.
        self.labelText.attributedText = NSAttributedString(string: slot.title, font: Font.semibold(13.0), textColor: .white)
        let textSize = self.labelText.updateLayout(CGSize(width: 220.0, height: 24.0))
        let labelSize = CGSize(width: ceil(textSize.width) + 16.0, height: ceil(textSize.height) + 6.0)
        self.labelNode.bounds = CGRect(origin: .zero, size: labelSize)
        self.labelBackground.frame = CGRect(origin: .zero, size: labelSize)
        self.labelBackground.update(size: labelSize, cornerRadius: labelSize.height / 2.0, transition: .immediate)
        self.labelText.frame = CGRect(origin: CGPoint(x: 8.0, y: 2.5), size: textSize)
        if let bubble = self.labelBubble {
            bubble.frame = CGRect(origin: .zero, size: labelSize)
            bubble.cornerRadius = labelSize.height / 2.0
            self.labelNode.addSubnode(bubble)
        } else {
            self.labelNode.addSubnode(self.labelBackground)
        }
        self.labelNode.addSubnode(self.labelText)

        self.bounds = CGRect(origin: .zero, size: size)
    }

    var labelSize: CGSize {
        return self.labelNode.bounds.size
    }

    private var labelVisible = false

    /// Подпись появляется с небольшим пружинным баунсом масштаба. Возвращает true, если подпись только что показана.
    @discardableResult
    func setLabelVisible(_ visible: Bool) -> Bool {
        if visible == self.labelVisible {
            return false
        }
        self.labelVisible = visible
        if visible {
            self.labelNode.alpha = 1.0
            self.labelNode.layer.animateAlpha(from: 0.0, to: 1.0, duration: 0.12)
            // damping ratio ≈ 0,6: перелёт масштаба около 8 %
            self.labelNode.layer.animateSpring(from: 0.85 as NSNumber, to: 1.0 as NSNumber, keyPath: "transform.scale", duration: 0.5, stiffness: 500.0, damping: 36.0)
        } else {
            swipeTransition(true).updateAlpha(node: self.labelNode, alpha: 0.0)
        }
        return true
    }

    private var isSelectedState = false

    /// Готовые Lottie Telegram (`anim_copy`, `anim_forward`, `anim_savemedia`) нарисованы под тосты: круг/белые заливки, при
    /// перекраске в цвет иконки превращаются в чёрные пятна. Поэтому при выборе иконка играет собственную пружину (CoreAnimation).
    func setSelected(_ selected: Bool) {
        if selected == self.isSelectedState {
            return
        }
        self.isSelectedState = selected
        if !selected || self.slot.kind == .reply || !self.slot.isEnabled {
            return
        }
    }
}

/// Одна сессия жеста: капсула слотов вокруг штатного кружка «Ответить».
public final class ChatSwipeActionsSession {
    private let slots: [ChatSwipeActionSlot]
    private let centerIndex: Int
    private let centerNode: ChatMessageSwipeToReplyNode
    private weak var parent: ASDisplayNode?
    private var nodes: [Int: ChatSwipeActionSlotNode] = [:]
    /// Слоты выходят за пределы ячейки, а соседние ячейки рисуются поверх неё: рейка живёт в прозрачном слое над всем окном.
    private let overlay: UIView?
    private let feedback = HapticFeedback()
    /// Копия штатного кружка поверх капсулы: стрелка и анимация успеха как в проде, подложка гаснет, пока капсула растёт.
    private var iconClone: ChatMessageSwipeToReplyNode?
    private let foregroundColor: UIColor

    private let capsuleContainer = GlassBackgroundContainerView()
    private let capsule = GlassBackgroundView()
    /// Один стеклянный кружок выбора (как ползунок сегментированного контрола): переезжает между слотами по Y пружиной.
    private let thumb = UIView()
    /// Раскрытие слотов от центра: 0 — все в центре, 1 — на местах. Считается из высоты капсулы, поэтому иконки баунсятся синхронно с ней.
    private var spread: CGFloat = 0.0
    /// Позиция капсулы фиксируется в момент появления: дальше она не едет за пальцем и пузырём.
    private var pinnedCenter: CGPoint?
    private var capsuleShown = false
    /// Серый ползунок появляется только когда палец первый раз ушёл от центра: на пороге (Reply выбран сам собой) он лишний.
    private var thumbRevealed = false
    private var growing = false
    /// Идёт схлопывание: серый ползунок не едет за разъезжающимися иконками, а гаснет на месте.
    private var collapsing = false
    /// Серая подложка под кружком на время роста капсулы: клон кружка свою не рисует, подложка гаснет, пока проявляется стекло.
    private let plateView = UIView()
    private var growAnimator: ConstantDisplayLinkAnimator?
    private var pulseAnimator: ConstantDisplayLinkAnimator?
    /// Относительное растяжение капсулы по высоте при баунсе выбора.
    private var pulse: CGFloat = 0.0
    private let isDark: Bool

    private let pitch: CGFloat = itemSize + itemSpacing
    private let deadZone: CGFloat = 14.0
    private let hysteresis: CGFloat = 6.0

    private var armed = false
    private var expanded = false
    private var anchorY: CGFloat = 0.0
    private var sign: CGFloat = 0.0
    private var level: Int = 0
    /// Смещение выбранного слота относительно центра: −1 вверх, 0 ответ, +1 вниз.
    public private(set) var selectedOffset = 0
    public var isArmed: Bool {
        return self.armed
    }

    public var selectedSlot: ChatSwipeActionSlot {
        return self.slots[self.centerIndex + self.selectedOffset]
    }

    private var capsuleHeight: CGFloat {
        return itemSize * CGFloat(self.slots.count) + itemSpacing * CGFloat(self.slots.count - 1) + capsulePadding * 2.0
    }

    public init(slots: [ChatSwipeActionSlot], centerNode: ChatMessageSwipeToReplyNode, parent: ASDisplayNode, labelFill: UIColor, enableBlur: Bool, foregroundColor: UIColor, capsuleColor: UIColor, isDark: Bool, wallpaper: WallpaperBackgroundNode? = nil, makeIcon: () -> ChatMessageSwipeToReplyNode) {
        self.foregroundColor = foregroundColor
        self.slots = slots
        self.centerIndex = slots.firstIndex(where: { $0.kind == .reply }) ?? 0
        self.centerNode = centerNode
        self.parent = parent
        self.isDark = isDark
        self.capsule.alpha = 0.0
        if let window = parent.view.window {
            let overlay = UIView(frame: window.bounds)
            overlay.isUserInteractionEnabled = false
            overlay.backgroundColor = .clear
            window.addSubview(overlay)
            self.overlay = overlay
        } else {
            self.overlay = nil
        }
        let host: UIView = self.overlay ?? parent.view
        // Как у навбара и чат-бара: стекло лежит в контейнере стекла.
        self.capsuleContainer.isUserInteractionEnabled = false
        self.capsuleContainer.frame = host.bounds
        self.capsuleContainer.update(size: host.bounds.size, isDark: isDark, transition: .immediate)
        host.addSubview(self.capsuleContainer)
        self.capsuleContainer.contentView.addSubview(self.capsule)
        self.thumb.alpha = 0.0
        self.thumb.isUserInteractionEnabled = false
        // Выбранный слот — серая заливка, как подсветка пункта в контекстном меню (чёрный/белый 10 %).
        self.thumb.backgroundColor = (isDark ? UIColor.white : UIColor.black).withAlphaComponent(0.1)
        self.thumb.layer.cornerRadius = itemSize / 2.0
        host.addSubview(self.thumb)
        self.feedback.prepareImpact()
        for (index, slot) in slots.enumerated() {
            let node = ChatSwipeActionSlotNode(slot: slot, labelFill: labelFill, enableBlur: enableBlur, foregroundColor: foregroundColor, wallpaper: wallpaper)
            node.alpha = 0.0
            node.isUserInteractionEnabled = false
            host.addSubview(node.view)
            host.addSubview(node.labelNode.view)
            self.nodes[index] = node
        }
        if self.overlay != nil {
            let icon = makeIcon()
            icon.bounds = CGRect(origin: .zero, size: CGSize(width: 33.0, height: 33.0))
            icon.alpha = 0.0
            icon.circularWaveEnabled = false
            let replyScale: CGFloat = ChatSwipeActionsPrototype.filledIcons ? 1.45 : 1.3
            icon.baseScale = replyScale
            icon.transform = CATransform3DMakeScale(replyScale, replyScale, 1.0)
            icon.isUserInteractionEnabled = false
            self.plateView.backgroundColor = isDark ? UIColor(white: 0.18, alpha: 0.9) : labelFill
            self.plateView.layer.cornerRadius = 16.5
            self.plateView.alpha = 0.0
            self.plateView.isUserInteractionEnabled = false
            host.addSubview(self.plateView)
            host.addSubview(icon.view)
            self.iconClone = icon
        }
    }

    private func boundary(_ level: Int) -> CGFloat {
        return self.deadZone + CGFloat(level - 1) * self.pitch
    }

    /// `translation` — сырой сдвиг пальца, `centerPosition` — позиция кружка ответа в координатах узла.
    public func update(translation: CGPoint, threshold: CGFloat, centerPosition: CGPoint) {
        self.iconClone?.updateProgress(self.centerNode.currentProgress)
        let reached = -translation.x >= threshold
        if !self.armed && reached {
            self.armed = true
            self.anchorY = translation.y
            self.sign = 0.0
            self.level = 0
            self.expanded = false
            self.selectedOffset = 0
            self.expanded = true   // «Ответить» выбран сразу: серый кружок и подпись видны уже на пороге
            self.setCapsuleShown(true, centerPosition: centerPosition)
            self.applyState(animated: true)
        } else if self.armed && -translation.x < threshold - 6.0 {
            self.armed = false
            self.expanded = false
            self.thumbRevealed = false
            self.selectedOffset = 0
            self.feedback.impact(.light)
            self.setCapsuleShown(false, centerPosition: centerPosition)
            self.applyState(animated: true)
        }
        if self.armed {
            let dy = translation.y - self.anchorY
            let magnitude = abs(dy)
            let direction: CGFloat = dy < 0.0 ? -1.0 : 1.0
            if self.sign != 0.0 && direction != self.sign {
                self.level = 0
            }
            let maxUp = self.centerIndex
            let maxDown = self.slots.count - 1 - self.centerIndex
            let maxLevel = direction < 0.0 ? maxUp : maxDown
            var level = self.level
            while level < maxLevel && magnitude >= self.boundary(level + 1) {
                level += 1
            }
            while level > 0 && magnitude < self.boundary(level) - self.hysteresis {
                level -= 1
            }
            self.level = level
            self.sign = level == 0 ? 0.0 : direction
            let offset = level == 0 ? 0 : Int(direction) * level
            if offset != self.selectedOffset {
                self.setSelected(offset: offset)
                if !self.expanded {
                    self.expanded = true
                    self.applyState(animated: true)
                }
            }
        }
        self.layout(centerPosition: self.capsuleShown ? (self.pinnedCenter ?? centerPosition) : centerPosition)
    }

    private func setSelected(offset: Int) {
        let previous = self.selectedOffset
        if offset != 0 {
            self.thumbRevealed = true
        }
        if previous != offset {
            // Кружок перетекает между слотами как ползунок сегментного контрола: без отскока, базовый изинг.
            let slide = CABasicAnimation(keyPath: "position.y")
            slide.fromValue = Double(CGFloat(previous - offset) * self.pitch)
            slide.toValue = 0.0
            slide.isAdditive = true
            slide.duration = 0.3
            slide.timingFunction = CAMediaTimingFunction(controlPoints: 0.0, 0.4, 0.0, 1.0)
            self.thumb.layer.add(slide, forKey: "slide")
        }
        self.selectedOffset = offset
        self.applyState(animated: true)
        if previous != offset {
            self.startPulse()
        }
    }

    /// Лёгкий баунс всей капсулы при смене действия: один толчок и возврат (затухающая пружина по высоте).
    private func startPulse() {
        if self.growing || !self.capsuleShown {
            return
        }
        let startTime = CACurrentMediaTime()
        let animator = ConstantDisplayLinkAnimator(update: { [weak self] in
            guard let self else {
                return
            }
            let t = CACurrentMediaTime() - startTime
            if t >= 0.6 || self.growing || !self.capsuleShown {
                self.pulse = 0.0
                self.pulseAnimator?.isPaused = true
                self.pulseAnimator = nil
            } else {
                self.pulse = CGFloat(0.11 * exp(-7.0 * t) * sin(12.2 * t))
            }
            self.layout(centerPosition: self.pinnedCenter ?? self.lastCenter)
        })
        self.pulseAnimator?.isPaused = true
        self.pulseAnimator = animator
        animator.isPaused = false
    }

    private func itemScale(index: Int) -> CGFloat {
        return 1.0
    }

    /// Появление/схлопывание капсулы: «жидкая» пружина из кружка ответа, иконки вылетают из центра по очереди (как в QuickShare).
    private func setCapsuleShown(_ shown: Bool, centerPosition: CGPoint) {
        if shown == self.capsuleShown {
            return
        }
        self.capsuleShown = shown
        self.labelsAllowedAt = shown ? CFAbsoluteTimeGetCurrent() + 0.18 : 0.0
        let full = CGSize(width: capsuleWidth, height: self.capsuleHeight)
        let small = CGSize(width: 33.0, height: 33.0)
        if shown {
            // Капсула вырастает из серой подложки кружка ответа: стартует ровно под ним (тот же серый тон, 33 pt) и растягивается.
            self.collapsing = false
            self.pinnedCenter = centerPosition
            self.layout(centerPosition: centerPosition)
            self.capsule.alpha = 1.0
            self.centerNode.alpha = 0.0
            if let icon = self.iconClone {
                // Исходный кружок остаётся на месте и отыгрывает штатную анимацию; под ним растёт серая капсула.
                icon.setPlateAlpha(0.0, duration: 0.0)
                icon.alpha = 1.0
                if ChatSwipeActionsPrototype.filledIcons {
                    icon.setAlternateForeground(color: self.foregroundColor, duration: 0.0)
                } else {
                    icon.playOutlineMorph(color: self.foregroundColor, duration: 0.3)
                }
                self.plateView.bounds = CGRect(x: 0.0, y: 0.0, width: 33.0, height: 33.0)
                self.plateView.center = self.lastCenter
                self.plateView.alpha = 1.0
                self.plateView.layer.removeAllAnimations()
                ContainedViewLayoutTransition.animated(duration: 0.3, curve: .linear).updateAlpha(layer: self.plateView.layer, alpha: 0.0)
            }
            self.capsule.layer.removeAllAnimations()
            self.capsule.layer.animateAlpha(from: 0.0, to: 1.0, duration: 0.18)
            let grow: (CGFloat, CGFloat) = (200.0, 27.0)     // капсула растёт мягче, без резкого скачка
            // Стекло растёт из кружка ответа: стартует 33 pt под ним и тянется до полной капсулы (мягкая пружина считается вручную,
            // чтобы маска и стекло меняли размер в одном кадре).
            self.growing = true
            let startTime = CACurrentMediaTime()
            let omega = Double(grow.0).squareRoot()
            let growStep: (CGFloat, CGFloat) -> Void = { [weak self] p, q in
                guard let self else {
                    return
                }
                // Ширина растёт без перелёта, высота — с небольшим отскоком (жидкое стекло).
                let size = CGSize(width: small.width + (full.width - small.width) * p, height: small.height + (full.height - small.height) * q)
                let center = self.lastCenter
                self.capsule.frame = CGRect(x: center.x - size.width / 2.0, y: center.y - size.height / 2.0, width: size.width, height: size.height)
                self.updateGlass(size: size, transition: .immediate)
                self.spread = q
                self.positionNodes(center: center)
            }
            growStep(0.0, 0.0)
            let animator = ConstantDisplayLinkAnimator(update: { [weak self] in
                guard let self else {
                    return
                }
                let t = CACurrentMediaTime() - startTime
                if t >= 1.0 {
                    growStep(1.0, 1.0)
                    self.growing = false
                    self.growAnimator?.isPaused = true
                    self.growAnimator = nil
                    return
                }
                let zeta = 0.55
                let wd = omega * (1.0 - zeta * zeta).squareRoot()
                let q = 1.0 - exp(-zeta * omega * t) * (cos(wd * t) + zeta * omega / wd * sin(wd * t))
                growStep(CGFloat(1.0 - (1.0 + omega * t) * exp(-omega * t)), CGFloat(q))
            })
            self.growAnimator = animator
            animator.isPaused = false
            for (index, node) in self.nodes {
                let delay = 0.04 + Double(abs(index - self.centerIndex)) * 0.03
                node.alpha = 0.0
                Queue.mainQueue().after(delay, { [weak self, weak node] in
                    guard let self, let node, self.capsuleShown else {
                        return
                    }
                    node.alpha = node.slot.isEnabled ? 1.0 : 0.4
                    // Как в нативе при переходе между навбарами: непрозрачность 0→1 и блюр 10→0 на мягкой пружине.
                    node.layer.animateAlpha(from: 0.0, to: 1.0, duration: 0.25)
                    ComponentTransition.easeInOut(duration: 0.3).animateBlur(layer: node.layer, fromRadius: 10.0, toRadius: 0.0)
                    // Иконки сами не баунсятся: их разброс по Y (`spread`) ведёт та же пружина, что и высоту капсулы.
                })
            }
        } else {
            self.growing = false
            self.growAnimator?.isPaused = true
            self.growAnimator = nil
            self.collapseCapsule(duration: 0.35, removeNodes: false)
            let transition = swipeTransition(true)
            transition.updateAlpha(node: self.centerNode, alpha: 1.0)
            if let icon = self.iconClone {
                transition.updateAlpha(node: icon, alpha: 0.0)
            }
        }
    }

    /// Бесшовное схлопывание: иконки съезжаются в центр, одновременно уходят в блюр и гаснут, капсула сжимается в кружок под Reply и тает.
    private func collapseCapsule(duration: Double, removeNodes: Bool) {
        self.growing = true
        self.collapsing = true
        self.growAnimator?.isPaused = true
        let startSize = self.capsule.bounds.size
        let endSize = CGSize(width: 33.0, height: 33.0)   // капсула сжимается в кружок Reply, улетает вправо и гаснет
        let startSpread = self.spread
        let startTime = CACurrentMediaTime()
        let fadeAlpha = ContainedViewLayoutTransition.animated(duration: duration * 0.5, curve: .linear)
        for node in self.nodes.values {
            fadeAlpha.updateAlpha(node: node, alpha: 0.0)
            ComponentTransition.easeInOut(duration: duration).animateBlur(layer: node.layer, fromRadius: 0.0, toRadius: 5.0)
            ContainedViewLayoutTransition.animated(duration: 0.12, curve: .easeInOut).updateAlpha(node: node.labelNode, alpha: 0.0)
            if removeNodes {
                Queue.mainQueue().after(duration + 0.02, { [weak node] in
                    node?.labelNode.removeFromSupernode()
                    node?.removeFromSupernode()
                })
            }
        }
        // Улетая вправо, капсула не просто уменьшается: она тает через непрозрачность и блюр.
        ComponentTransition.easeInOut(duration: duration * 0.65).animateBlur(layer: self.capsule.layer, fromRadius: 0.0, toRadius: 8.0)
        let thumbFade = ContainedViewLayoutTransition.animated(duration: duration * 0.4, curve: .easeInOut)
        thumbFade.updateAlpha(layer: self.thumb.layer, alpha: 0.0)
        // Сильная ссылка на себя намеренно: сессию отпускают сразу после finish, а схлопывание ещё идёт; цикл рвётся в конце.
        let animator = ConstantDisplayLinkAnimator(update: { [self] in
            let t = min(1.0, (CACurrentMediaTime() - startTime) / duration)
            // Стекло сжимается ровно, пока гаснет: до пустого кружка дело не доходит, иконки и стекло исчезают вместе.
            // Сначала быстро (за 40 % времени) сжимается в кружок, дальше кружок ещё уменьшается, пока летит вслед за пузырём.
            let q = CGFloat(min(1.0, t / 0.45))
            let flight = 1.0 - 0.45 * CGFloat(t)
            let size = CGSize(width: (startSize.width + (endSize.width - startSize.width) * q) * flight, height: (startSize.height + (endSize.height - startSize.height) * q) * flight)
            // Как серый кружок Reply в проде: уменьшается и улетает вправо вслед за пузырём.
            // Якорь — пузырь: если список скроллится, пока капсула улетает, она едет вместе с ним.
            // Берём то, что сейчас на экране (presentation), а не итоговое положение: пузырь возвращается вправо, и капсула едет с ним
            // ровно так же, как серый кружок Reply в проде, без собственного смещения сверху.
            var center = self.lastCenter
            if let overlay = self.overlay, let parent = self.parent, let local = self.lastLocalCenter, parent.view.window != nil {
                let source = parent.view.layer.presentation() ?? parent.view.layer
                center = source.convert(local, to: overlay.layer)
            }
            self.iconClone?.position = center
            self.capsule.frame = CGRect(x: center.x - size.width / 2.0, y: center.y - size.height / 2.0, width: size.width, height: size.height)
            self.updateGlass(size: size, transition: .immediate)
            // К 80 % пути стекла уже нет: над шторкой не торчит.
            let fade = max(0.0, 1.0 - t / 0.6)
            self.capsule.alpha = CGFloat(fade * fade)
            self.spread = startSpread * (1.0 - q)
            self.positionNodes(center: center)
            if t >= 1.0 {
                self.growAnimator?.isPaused = true
                self.growAnimator = nil
            }
        })
        self.growAnimator = animator
        animator.isPaused = false
    }

    private var lastCenter = CGPoint()
    private var lastLocalCenter: CGPoint?
    /// Подписи не показываются, пока капсула только выезжает (первые ~0,18 с).
    private var labelsAllowedAt: CFAbsoluteTime = 0.0

    private func applyState(animated: Bool) {
        let transition = swipeTransition(animated)
        transition.updateAlpha(layer: self.thumb.layer, alpha: self.capsuleShown && self.armed && self.expanded && self.thumbRevealed ? 1.0 : 0.0)
        let wait = self.labelsAllowedAt - CFAbsoluteTimeGetCurrent()
        if wait > 0.0 {
            Queue.mainQueue().after(wait, { [weak self] in
                self?.applyState(animated: true)
            })
        }
        for (index, node) in self.nodes {
            let isSelected = index - self.centerIndex == self.selectedOffset
            let showLabel = self.armed && self.expanded && isSelected
            let labelAppeared = node.setLabelVisible(showLabel && self.capsuleShown && wait <= 0.0)
            if labelAppeared {
                self.feedback.tap()
            }
            node.setSelected(self.capsuleShown && self.armed && self.expanded && isSelected)
            guard self.capsuleShown else {
                continue
            }
            transition.updateTransformScale(node: node, scale: self.itemScale(index: index))
        }
    }

    private func nodePosition(index: Int, center: CGPoint) -> CGPoint {
        return CGPoint(x: center.x, y: center.y + CGFloat(index - self.centerIndex) * self.pitch * self.spread * (1.0 + self.pulse))
    }

    private func updateGlass(size: CGSize, transition: ComponentTransition) {
        self.capsule.update(size: size, cornerRadius: size.width / 2.0, isDark: self.isDark, tintColor: .init(kind: .panel), transition: transition)
    }

    private func layout(centerPosition: CGPoint) {
        var center = centerPosition
        if let overlay = self.overlay, let parent = self.parent {
            center = parent.view.convert(centerPosition, to: overlay)
        }
        self.lastCenter = center
        self.lastLocalCenter = centerPosition
        self.iconClone?.position = center
        let size = CGSize(width: capsuleWidth, height: self.capsuleHeight * (1.0 + self.pulse))
        let frame = CGRect(origin: CGPoint(x: center.x - size.width / 2.0, y: center.y - size.height / 2.0), size: size)
        if !self.growing {
            self.capsule.frame = frame
            self.updateGlass(size: size, transition: .immediate)
        }
        self.positionNodes(center: center)
    }

    private func positionNodes(center: CGPoint) {
        for (index, node) in self.nodes {
            let position = self.nodePosition(index: index, center: center)
            node.position = position
            // Подпись выбранного слота — слева от капсулы, на уровне слота.
            node.labelNode.position = CGPoint(x: center.x - capsuleWidth / 2.0 - 10.0 - node.labelSize.width / 2.0, y: position.y)
        }
        let selectedIndex = self.centerIndex + self.selectedOffset
        if !self.collapsing {
            self.thumb.bounds = CGRect(origin: .zero, size: CGSize(width: itemSize, height: itemSize))
            self.thumb.center = self.nodePosition(index: selectedIndex, center: center)
        }
    }

    /// Завершение жеста. `performed` — палец отпущен в «взведённом» состоянии. Возвращает выбранный слот или nil при отмене.
    public func finish(performed: Bool) -> ChatSwipeActionSlot? {
        let selected = performed && self.armed ? self.selectedSlot : nil
        if let selected, !selected.isEnabled {
            self.feedback.error()
        } else if selected != nil {
            self.feedback.success()
        }
        if !self.capsuleShown {
            centerNode.alpha = 1.0
        }
        let overlay = self.overlay
        Queue.mainQueue().after(0.35, {
            overlay?.removeFromSuperview()
        })
        // Выбор подтверждается: иконки съезжаются в центр через блюр и непрозрачность, капсула сжимается в кружок под Reply.
        if self.capsuleShown {
            self.collapseCapsule(duration: 0.3, removeNodes: true)
        } else {
            ContainedViewLayoutTransition.animated(duration: 0.15, curve: .linear).updateAlpha(layer: self.capsule.layer, alpha: 0.0)
            for node in self.nodes.values {
                Queue.mainQueue().after(0.4, { [weak node] in
                    node?.labelNode.removeFromSupernode()
                    node?.removeFromSupernode()
                })
            }
        }
        if let icon = self.iconClone {
            if self.capsuleShown {
                // Прежний кружок Reply при схлопывании не показываем: сворачивается только капсула.
                icon.layer.removeAllAnimations()
                icon.alpha = 0.0
                // Штатный код хоста запускает у исходного кружка свой полёт (масштаб + прозрачность): прячем его целиком.
                self.centerNode.isHidden = true
                self.plateView.layer.removeAllAnimations()
                self.plateView.alpha = 0.0
            } else {
                ContainedViewLayoutTransition.animated(duration: 0.15, curve: .linear).updateAlpha(node: icon, alpha: 0.0)
            }
        }
        return selected
    }
}
