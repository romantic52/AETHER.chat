import SwiftUI

// Пузырь сообщения: текст, хвост, статусы ✓/✓✓, реакции, свайп-ответ, контекст-меню.
struct MessageBubble: View {
    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()
    let message: ChatMessage
    let isGroup: Bool
    /// Канал: посты всегда слева (стиль входящих), независимо от автора.
    var channelStyle: Bool = false
    /// Просмотры поста (только каналы): глазик + число рядом со временем.
    var viewCount: Int? = nil
    let showTail: Bool
    let showSender: Bool
    let myId: String
    let readTick: Color

    /// Тап по цитате ответа — прыжок к оригинальному сообщению.
    var onQuoteTap: (() -> Void)? = nil
    var onReply: () -> Void
    var onQuickReact: () -> Void
    var onPicker: () -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void
    var onRetry: () -> Void
    /// Показать «О сообщении». Необязательный: у каналов и превью его нет.
    var onInfo: (() -> Void)? = nil

    @Environment(\.palette) private var palette
    @State private var dragX: CGFloat = 0

    private var outgoing: Bool { channelStyle ? false : message.outgoing }
    private var payload: Wire.Payload? { message.payload }

    var body: some View {
        HStack {
            if outgoing { Spacer(minLength: 50) }
            // Жест ответа висит на самом пузыре, а не на всей строке: пустое место
            // рядом с сообщением остаётся свободным (скролл/системный свайп-назад).
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 2) {
                if showSender, let sender = displaySender {
                    Text(sender)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(palette.accent)
                        .padding(.leading, 12)
                }
                bubbleBody
                if !message.reactions.isEmpty {
                    reactionChips
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: message.reactions)
            .offset(x: dragX)
            .animation(.interactiveSpring(response: 0.14, dampingFraction: 0.86), value: dragX)
            // Быстрая реакция — двойной тап по пузырю (одиночные тапы внутри
            // медиа сохраняются: SwiftUI ждёт короткое окно двойного тапа).
            .onTapGesture(count: 2) { onQuickReact() }
            .gesture(replySwipe)
            .contextMenu { contextMenu }
            if !outgoing { Spacer(minLength: 50) }
        }
    }

    private var displaySender: String? {
        showSender ? message.senderId : nil
    }

    @ViewBuilder private var bubbleBody: some View {
        if let p = payload, p.type == "media" {
            if p.mediaKind == .videoNote {
                // Кружок — без прямоугольного пузыря; цитата ответа — чипом сверху.
                VStack(alignment: outgoing ? .trailing : .leading, spacing: 6) {
                    if let rid = p.replyToId, !rid.isEmpty {
                        replyQuote(p.replyToText ?? "")
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(palette.bubbleIn, in: RoundedRectangle(cornerRadius: Radius.nested, style: .continuous))
                    }
                    MediaBubbleContent(message: message, payload: p, outgoing: outgoing)
                }
                .overlay(alignment: .bottomTrailing) { statusRow(scrim: true).padding(4) }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    if let rid = p.replyToId, !rid.isEmpty {
                        replyQuote(p.replyToText ?? "")
                            .padding(.horizontal, 8).padding(.top, 8)
                    }
                    MediaBubbleContent(message: message, payload: p, outgoing: outgoing)
                }
                .modifier(BubbleContainer(outgoing: outgoing, showTail: showTail, palette: palette))
                // Тёмная подложка-капсула нужна только там, где под метой
                // картинка (фото/видео). На голосовых и файлах пузырь и так
                // цветной — чёрный овал там выглядел инородно.
                .overlay(alignment: .bottomTrailing) {
                    statusRow(scrim: p.mediaKind == .image || p.mediaKind == .video)
                        .padding(6)
                }
            }
        } else {
            textBubble
        }
    }

    private var textBubble: some View {
        // В Telegram время у коротких сообщений пишется в ту же строку справа.
        // Чтобы добиться этого в SwiftUI, мы используем ZStack: текст имеет
        // отступ справа, а статус (время + галочки) всегда прибит в правый нижний угол.
        ZStack(alignment: .bottomTrailing) {
            VStack(alignment: .leading, spacing: 4) {
                if let rid = payload?.replyToId, !rid.isEmpty {
                    replyQuote(payload?.replyToText ?? "")
                }
                Text(payload?.text ?? "")
                    .font(.system(size: 16))
                    .foregroundStyle(outgoing ? palette.bubbleOutText : palette.textPrimary)
            }
            .padding(.trailing, 54) // Оставляем место для времени и галочек
            .padding(.bottom, 2)

            HStack(spacing: 4) {
                if channelStyle, let views = viewCount {
                    Image(systemName: "eye.fill").font(.system(size: 9))
                        .foregroundStyle(bubbleSecondary)
                    Text(Self.compactCount(views))
                        .font(.system(size: 10)).foregroundStyle(bubbleSecondary)
                }
                if message.edited {
                    Text("изм.").font(.system(size: 10)).foregroundStyle(bubbleSecondary)
                }
                Text(timeString)
                    .font(.system(size: 10))
                    .foregroundStyle(bubbleSecondary)
                if outgoing { statusIcon }
            }
            .padding(.bottom, -2)
            .padding(.trailing, -2)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .modifier(BubbleContainer(outgoing: outgoing, showTail: showTail, palette: palette))
    }

    /// 1 200 → «1.2K», как в Telegram.
    static func compactCount(_ n: Int) -> String {
        switch n {
        case ..<1000: return "\(n)"
        case ..<1_000_000: return String(format: "%.1fK", Double(n) / 1000).replacingOccurrences(of: ".0K", with: "K")
        default: return String(format: "%.1fM", Double(n) / 1_000_000).replacingOccurrences(of: ".0M", with: "M")
        }
    }

    private func replyQuote(_ text: String) -> some View {
        // Крупная цитата с подложкой (как в Telegram); тап — прыжок к оригиналу.
        HStack(spacing: 8) {
            Capsule().fill(outgoing ? Color.white.opacity(0.85) : palette.accent)
                .frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text("Ответ")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(outgoing ? .white.opacity(0.9) : palette.accent)
                Text(text.isEmpty ? "Сообщение" : text)
                    .font(.system(size: 14))
                    .foregroundStyle(bubbleSecondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
        .background(
            (outgoing ? Color.white.opacity(0.12) : palette.accent.opacity(0.10)),
            in: RoundedRectangle(cornerRadius: Radius.nested, style: .continuous)
        )
        .contentShape(Rectangle())
        .onTapGesture { onQuoteTap?() }
    }

    private var reactionChips: some View {
        HStack(spacing: 4) {
            ForEach(message.reactions.sorted { $0.key < $1.key }, id: \.key) { emoji, users in
                HStack(spacing: 2) {
                    Text(emoji).font(.system(size: 13))
                    if users.count > 1 { Text("\(users.count)").font(.system(size: 11, weight: .semibold)) }
                }
                .padding(.horizontal, 7).padding(.vertical, 3)
                .background(
                    users.contains(myId) ? palette.accent.opacity(0.25) : palette.surfaceElevated,
                    in: Capsule()
                )
                .foregroundStyle(palette.textPrimary)
            }
        }
        .padding(outgoing ? .trailing : .leading, 8)
    }

    /// scrim=true — мета поверх картинки: белый текст на тёмной капсуле.
    /// scrim=false — на цветном пузыре (гс/файл): цвета темы, без подложки.
    private func statusRow(scrim: Bool) -> some View {
        let tint: Color = scrim ? .white.opacity(0.85) : bubbleSecondary
        return HStack(spacing: 3) {
            if channelStyle, let views = viewCount {
                Image(systemName: "eye.fill").font(.system(size: 9)).foregroundStyle(tint)
                Text(Self.compactCount(views)).font(.system(size: 10)).foregroundStyle(tint)
            }
            Text(timeString).font(.system(size: 10)).foregroundStyle(tint)
            if outgoing { statusIcon(secondary: tint) }
        }
        .padding(.horizontal, 6).padding(.vertical, 2)
        .background(scrim ? Color.black.opacity(0.28) : .clear, in: Capsule())
    }

    private var statusIcon: some View { statusIcon(secondary: bubbleSecondary) }

    @ViewBuilder private func statusIcon(secondary: Color) -> some View {
        switch message.status {
        case 0: Image(systemName: "clock").font(.system(size: 10)).foregroundStyle(secondary)
        case 1: Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).foregroundStyle(secondary)
        case 2: doubleCheck(color: secondary)
        case 3: doubleCheck(color: readTick)
        case -1: Image(systemName: "exclamationmark.circle").font(.system(size: 11)).foregroundStyle(palette.danger)
        // Ждёт получателя рядом: не ошибка и не «отправляется» — сообщение
        // намеренно никуда не уедет, пока человек не окажется поблизости.
        case 4: Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 10)).foregroundStyle(secondary)
        default: EmptyView()
        }
    }

    private func doubleCheck(color: Color) -> some View {
        ZStack {
            Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).offset(x: -3)
            Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).offset(x: 1)
        }.foregroundStyle(color)
    }

    private var bubbleSecondary: Color {
        outgoing ? palette.bubbleOutText.opacity(0.68) : palette.textSecondary
    }

    private var timeString: String {
        Self.timeFormatter.string(from: message.date)
    }

    // Свайп ВЛЕВО по пузырю → ответ (как в Telegram). Правый край жесту
    // не мешает, а левый остаётся системному свайпу-назад.
    private var replySwipe: some Gesture {
        DragGesture(minimumDistance: 20, coordinateSpace: .global)
            .onChanged { v in
                // Компенсация порога распознавания (20pt): к первому событию палец
                // уже уехал — без вычета пузырь «телепортировался» на эти 20pt.
                let shifted = v.translation.width + 20
                guard shifted < 0 else {
                    if dragX != 0 { dragX = 0 }
                    return
                }
                // Резинка: после -58 движение с сопротивлением, жёсткий предел -80.
                let x = max(shifted, -110)
                dragX = x < -58 ? -58 + (x + 58) * 0.3 : x
            }
            .onEnded { v in
                if v.translation.width + 20 < -55 { onReply() }
                withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) { dragX = 0 }
            }
    }

    @ViewBuilder private var contextMenu: some View {
        if message.status == -1 {
            Button { onRetry() } label: { Label("Отправить снова", systemImage: "arrow.clockwise") }
        }
        Button { onReply() } label: { Label("Ответить", systemImage: "arrowshape.turn.up.left") }
        Button { onQuickReact() } label: { Label("Реакция", systemImage: "heart") }
        Button { onPicker() } label: { Label("Выбрать реакцию", systemImage: "face.smiling") }
        if let p = payload, p.type == "text" {
            Button {
                UIPasteboard.general.string = p.text
            } label: { Label("Копировать", systemImage: "doc.on.doc") }
            // Авторство — по message.outgoing, а не по outgoing (в канале свои
            // посты рисуются слева как «входящие», но править/удалять их можно).
            if message.outgoing {
                Button { onEdit() } label: { Label("Изменить", systemImage: "pencil") }
            }
        }
        if let onInfo {
            Button { onInfo() } label: { Label("О сообщении", systemImage: "info.circle") }
        }
        if message.outgoing {
            Button(role: .destructive) { onDelete() } label: { Label("Удалить", systemImage: "trash") }
        }
    }
}

// Форма пузыря с хвостом (как в Telegram): скруглённый прямоугольник, у хвостовой
// стороны нижний угол острый. Все пузыри — liquid glass; исходящие с лёгким акцентным тинтом.
struct BubbleContainer: ViewModifier {
    let outgoing: Bool
    let showTail: Bool
    let palette: Palette
    @EnvironmentObject var appearance: AppearanceSettings

    func body(content: Content) -> some View {
        let tailEnabled = appearance.bubbleTails && showTail
        let shape = BubbleShapeNew(outgoing: outgoing, tail: tailEnabled,
                                   radius: CGFloat(appearance.bubbleRadius),
                                   connected: CGFloat(appearance.bubbleConnected))
        let tailW: CGFloat = tailEnabled ? 6 : 0
        // Пузыри — плоские цвета темы, БЕЗ glassEffect: стекло на каждой строке
        // ленты — это отдельный blur-проход на сообщение, и скролл заметно лагает
        // (стекло остаётся только на панелях: шапка, композер, таб-бар).
        content
            .padding(.trailing, outgoing ? tailW : 0)
            .padding(.leading, outgoing ? 0 : tailW)
            .background(shape.fill(outgoing ? palette.bubbleOut : palette.bubbleIn))
            // Клип по форме пузыря: без него фото/видео торчат за скругления,
            // и «сообщение с картинкой» выглядит как голая прямоугольная картинка.
            .clipShape(shape)
            // Хит-зона и зона контекст-меню — строго форма пузыря: следом идёт
            // широкий выравнивающий frame (78% экрана), и без явного contentShape
            // жесты ловились в невидимой области вокруг пузыря.
            .contentShape(.interaction, shape)
            .contentShape(.contextMenuPreview, shape)
            .overlay(shape.stroke(Color.white.opacity(0.06), lineWidth: 0.5))
            .frame(maxWidth: min(420, UIScreen.main.bounds.width * 0.78),
                   alignment: outgoing ? .trailing : .leading)
    }
}

struct BubbleShape: Shape {
    let outgoing: Bool
    let tail: Bool
    func path(in rect: CGRect) -> Path {
        let tailWidth: CGFloat = tail ? 4 : 0
        let body = outgoing
            ? CGRect(x: rect.minX, y: rect.minY, width: rect.width - tailWidth, height: rect.height)
            : CGRect(x: rect.minX + tailWidth, y: rect.minY, width: rect.width - tailWidth, height: rect.height)
        let connected = AetherUI.connectedRadius
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: AetherUI.bubbleRadius,
            bottomLeadingRadius: outgoing ? AetherUI.bubbleRadius : (tail ? 0 : connected),
            bottomTrailingRadius: outgoing ? (tail ? 0 : connected) : AetherUI.bubbleRadius,
            topTrailingRadius: AetherUI.bubbleRadius,
            style: .continuous
        )
        var path = shape.path(in: body)
        guard tail else { return path }

        if outgoing {
            path.move(to: CGPoint(x: body.maxX - 5, y: body.maxY - 10))
            path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.maxY),
                              control: CGPoint(x: body.maxX, y: body.maxY - 2))
            path.addQuadCurve(to: CGPoint(x: body.maxX - 8, y: body.maxY - 2),
                              control: CGPoint(x: body.maxX - 2, y: body.maxY))
        } else {
            path.move(to: CGPoint(x: body.minX + 5, y: body.maxY - 10))
            path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY),
                              control: CGPoint(x: body.minX, y: body.maxY - 2))
            path.addQuadCurve(to: CGPoint(x: body.minX + 8, y: body.maxY - 2),
                              control: CGPoint(x: body.minX + 2, y: body.maxY))
        }
        path.closeSubpath()
        return path
    }
}

// Простой хвостик-треугольник для liquid glass пузыря.
struct TailShape: Shape {
    let outgoing: Bool
    func path(in rect: CGRect) -> Path {
        var path = Path()
        if outgoing {
            path.move(to: CGPoint(x: rect.minX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        } else {
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        }
        path.closeSubpath()
        return path
    }
}
import SwiftUI

struct BubbleShapeNew: Shape {
    let outgoing: Bool
    let tail: Bool
    var radius: CGFloat = AetherUI.bubbleRadius
    var connected: CGFloat = AetherUI.connectedRadius
    
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let r = radius
        let c = connected
        let tailW: CGFloat = 6 // Всегда резервируем место для выравнивания
        
        if outgoing {
            let bodyR = rect.maxX - tailW
            
            path.move(to: CGPoint(x: rect.minX, y: rect.minY + r))
            path.addArc(center: CGPoint(x: rect.minX + r, y: rect.minY + r), radius: r, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
            
            path.addLine(to: CGPoint(x: bodyR - r, y: rect.minY))
            path.addArc(center: CGPoint(x: bodyR - r, y: rect.minY + r), radius: r, startAngle: .degrees(270), endAngle: .degrees(360), clockwise: false)
            
            if tail {
                path.addLine(to: CGPoint(x: bodyR, y: rect.maxY - 12))
                path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.maxY), control: CGPoint(x: bodyR, y: rect.maxY - 1))
                path.addQuadCurve(to: CGPoint(x: bodyR - 10, y: rect.maxY), control: CGPoint(x: bodyR - 2, y: rect.maxY))
            } else {
                path.addLine(to: CGPoint(x: bodyR, y: rect.maxY - c))
                path.addArc(center: CGPoint(x: bodyR - c, y: rect.maxY - c), radius: c, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
            }
            
            path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
            path.addArc(center: CGPoint(x: rect.minX + r, y: rect.maxY - r), radius: r, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
            path.closeSubpath()
        } else {
            let bodyL = rect.minX + tailW
            
            path.move(to: CGPoint(x: bodyL + r, y: rect.minY))
            path.addArc(center: CGPoint(x: rect.maxX - r, y: rect.minY + r), radius: r, startAngle: .degrees(270), endAngle: .degrees(360), clockwise: false)
            
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
            path.addArc(center: CGPoint(x: rect.maxX - r, y: rect.maxY - r), radius: r, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
            
            if tail {
                path.addLine(to: CGPoint(x: bodyL + 10, y: rect.maxY))
                path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY), control: CGPoint(x: bodyL + 2, y: rect.maxY))
                path.addQuadCurve(to: CGPoint(x: bodyL, y: rect.maxY - 12), control: CGPoint(x: bodyL, y: rect.maxY - 1))
            } else {
                path.addLine(to: CGPoint(x: bodyL + c, y: rect.maxY))
                path.addArc(center: CGPoint(x: bodyL + c, y: rect.maxY - c), radius: c, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
            }
            
            path.addLine(to: CGPoint(x: bodyL, y: rect.minY + r))
            path.addArc(center: CGPoint(x: bodyL + r, y: rect.minY + r), radius: r, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
            path.closeSubpath()
        }
        return path
    }
}
