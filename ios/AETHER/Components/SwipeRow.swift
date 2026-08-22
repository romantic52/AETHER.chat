import SwiftUI
import UIKit

/// Настройки ощущения свайпа — все в одном месте, чтобы подбирать не по коду.
/// Числа отталкиваются от разбора клиента Telegram: круг 50pt, поля 10pt,
/// пружина 420/40, растягивание до срабатывания ~100pt.
enum SwipeTuning {
    // Пружины строятся с НАЧАЛЬНОЙ СКОРОСТЬЮ пальца. Без неё строка на
    // отпускании сначала замирала и только потом пружинила — на резком движении
    // это читалось как излом. Скорость передаётся долями расстояния в секунду.
    static func open(velocity: Double) -> Animation {
        .interpolatingSpring(stiffness: 300, damping: 34, initialVelocity: velocity)
    }
    static func close(velocity: Double) -> Animation {
        .interpolatingSpring(stiffness: 190, damping: 28, initialVelocity: velocity)
    }
    static func settle(velocity: Double) -> Animation {
        .interpolatingSpring(stiffness: 260, damping: 30, initialVelocity: velocity)
    }
    /// Насколько дальше порога срабатывания строку вообще можно утащить.
    /// У Telegram ход упирается, а не тянется бесконечно.
    static let overshootLimit: CGFloat = 40

    // Форма снята с эталона покадрово: скруглённый прямоугольник 66×42 с
    // радиусом 18 — плоские верх и низ, сильно закруглённые углы. Подпись
    // серым ПОД плашкой. Не эллипс: у эллипса острые бока, и он выглядит
    // сплюснутым кругом.
    static let actionWidth: CGFloat = 62      // сама плашка
    static let actionHeight: CGFloat = 42
    static let actionRadius: CGFloat = 18
    /// Место под кнопку шире плашки: подпись длиннее её и не должна обрезаться.
    static let slotWidth: CGFloat = 80
    static let inset: CGFloat = 10
    /// Сколько нужно перетянуть за открытое состояние до срабатывания.
    static let stretchDistance: CGFloat = 100
    /// Вязкость хода за пределом открытого состояния (1 — без сопротивления).
    static let resistance: CGFloat = 0.75
    /// Доля возврата, после которой отпускание закрывает строку.
    static let closeThreshold: CGFloat = 0.6
}

/// Действие, открывающееся свайпом строки.
struct RowAction: Identifiable {
    let id = UUID()
    var title: LocalizedStringKey
    var icon: String
    var tint: Color
    var perform: () -> Void
}

/// Строка списка со свайпами — замена `swipeActions`, который существует только
/// внутри `List`. Обе стороны работают одинаково: ряд кнопок прорастает
/// масштабом, крайняя кнопка растягивается при перетягивании, отпускание за
/// порогом выполняет её действие.
struct SwipeRow<Content: View>: View {
    var rowId: String
    @Binding var openRow: String?
    var leading: [RowAction] = []
    var trailing: [RowAction] = []
    /// Перетягивание влево-вправо выполняет крайнюю кнопку соответствующей стороны.
    var fullSwipeLeading = true
    var fullSwipeTrailing = true
    /// В режиме правки список перетаскивают — свайп там только мешает.
    var swipeEnabled = true
    var onTap: () -> Void
    #if DEBUG
    /// Стенд прогоняет жест сам: последовательность смещений в точках, потом
    /// отпускание. Нужно, чтобы проверять свайп в симуляторе без пальца —
    /// инъекция касаний там доступна не всегда.
    var debugScript: [CGFloat]? = nil
    #endif
    @ViewBuilder var content: Content

    @Environment(\.palette) private var palette
    @EnvironmentObject private var appearance: AppearanceSettings
    @State private var offset: CGFloat = 0
    @State private var horizontal: Bool?
    @State private var dragStart: CGFloat = 0
    /// Сторона, выбранная в начале жеста: +1 ведущая, -1 ведомая. Пока жест
    /// не отпущен, перейти на другую сторону нельзя — иначе одним свайпом
    /// строка закрывалась и тут же открывалась зеркально.
    @State private var lockedSide: CGFloat = 0
    /// Сбрасывается системой при отмене жеста — без этого флаг направления
    /// залипал и список переставал прокручиваться.
    @GestureState private var gestureActive = false

    private var actionW: CGFloat { SwipeTuning.actionWidth }
    private var slotW: CGFloat { SwipeTuning.slotWidth }
    private var actionH: CGFloat { SwipeTuning.actionHeight }
    private var inset: CGFloat { SwipeTuning.inset }

    private func rowWidth(_ count: Int) -> CGFloat {
        CGFloat(count) * slotW + inset
    }
    private var leadingWidth: CGFloat { rowWidth(leading.count) }
    private var trailingWidth: CGFloat { rowWidth(trailing.count) }

    /// Насколько открыт ряд СВОЕЙ стороны. Раньше величина была общей, и при
    /// свайпе вправо противоположная группа тоже проявлялась — на экране
    /// оказывались обе сразу, закрепление и удаление вперемешку.
    private func revealProgress(fromLeading: Bool) -> CGFloat {
        let travel = fromLeading ? max(offset, 0) : max(-offset, 0)
        let full = fromLeading ? leadingWidth : trailingWidth
        return min(travel / max(full, 1), 1)
    }
    /// 0 → обычное открытие, 1 → крайняя кнопка растянута и отпускание
    /// выполнит её действие. Величина НЕПРЕРЫВНАЯ, поэтому переход плавный.
    private var stretchLeading: CGFloat {
        guard fullSwipeLeading, !leading.isEmpty else { return 0 }
        return min(max((offset - leadingWidth) / CGFloat(appearance.swipeStretch), 0), 1)
    }
    private var stretchTrailing: CGFloat {
        guard fullSwipeTrailing, !trailing.isEmpty else { return 0 }
        return min(max((-offset - trailingWidth) / CGFloat(appearance.swipeStretch), 0), 1)
    }

    var body: some View {
        ZStack {
            // Рисуем только ту сторону, которую действительно тянут: так
            // противоположная не может ни проступить, ни поймать нажатие.
            if offset > 0 {
                leadingLayer.frame(maxWidth: .infinity, alignment: .leading)
            }
            if offset < 0 {
                trailingLayer.frame(maxWidth: .infinity, alignment: .trailing)
            }

            content
                .background(palette.background)
                .offset(x: offset)
                .contentShape(Rectangle())
                .onTapGesture {
                    if offset != 0 { close() } else { onTap() }
                }
        }
        // Обрезка ПОСТОЯННАЯ. Пробовал включать её только на время свайпа —
        // это условие меняет идентичность вьюхи, и SwiftUI пересоздаёт строку
        // вместе с состоянием и распознавателем ровно в момент начала жеста:
        // свайп переставал открываться вовсе.
        .clipped()
        // Вся строка — площадь для свайпа: аватарка и время не «глухие».
        .contentShape(Rectangle())
        .modifier(PanAttach(
            enabled: swipeEnabled,
            onChanged: { panChanged($0) },
            onEnded: { panEnded(translation: $0, velocity: $1) }
        ))
        .onChange(of: gestureActive) { _, active in if !active { horizontal = nil } }
        .onChange(of: openRow) { _, id in if id != rowId, offset != 0 { close() } }
        #if DEBUG
        .task {
            guard let script = debugScript else { return }
            try? await Task.sleep(nanoseconds: 700_000_000)
            for dx in script {
                panChanged(dx)
                try? await Task.sleep(nanoseconds: 40_000_000)
            }
            try? await Task.sleep(nanoseconds: 600_000_000)
            panEnded(translation: script.last ?? 0, velocity: 0)
        }
        #endif
    }

    // MARK: - Кнопки

    /// Ряд кнопок стоит на своём месте и по ширине не меняется: появление —
    /// масштаб и прозрачность, то есть трансформации, без пересчёта раскладки.
    ///
    /// Растёт ТА ЖЕ САМАЯ крайняя кнопка, а не новая поверх ряда: её плашка
    /// удлиняется от края внутрь и накрывает соседние, которые к этому моменту
    /// уже погасли. Отдельный слой поверх выглядел как «появилась вторая
    /// кнопка» — это было неправильно.
    private func layer(_ actions: [RowAction], fullWidth: CGFloat,
                       stretch: CGFloat, fromLeading: Bool) -> some View {
        let edgeIndex = fromLeading ? 0 : actions.count - 1
        // Насколько удлинилась плашка — на столько же РАСТАЛКИВАЕТ соседей.
        // Раньше она просто накрывала их сверху, и было видно, как иконки
        // исчезают под ней.
        let available = max(abs(offset) - 2 * inset, actionW)
        let push = (available - actionW) * stretch
        return HStack(spacing: 0) {
            ForEach(Array(actions.enumerated()), id: \.element.id) { index, action in
                let isEdge = index == edgeIndex
                button(action, stretch: isEdge ? stretch : 0, fromLeading: fromLeading,
                       reveal: revealProgress(fromLeading: fromLeading))
                    .offset(x: isEdge ? 0 : (fromLeading ? push : -push))
                    .zIndex(isEdge ? 1 : 0)
            }
        }
        .padding(fromLeading ? .leading : .trailing, inset)
        .frame(width: fullWidth, alignment: fromLeading ? .leading : .trailing)
    }

    private var leadingLayer: some View {
        layer(leading, fullWidth: leadingWidth, stretch: stretchLeading, fromLeading: true)
    }
    private var trailingLayer: some View {
        layer(trailing, fullWidth: trailingWidth, stretch: stretchTrailing, fromLeading: false)
    }

    /// Слот одинаковый у всех, плашка по центру, подпись под ней. У крайней
    /// кнопки плашка удлиняется наружу слота — выходить за него ей можно,
    /// обрезает только сама строка.
    private func button(_ a: RowAction, stretch: CGFloat = 0,
                        fromLeading: Bool = true, reveal: CGFloat = 1) -> some View {
        let available = max(abs(offset) - 2 * inset, actionW)
        let plateWidth = actionW + (available - actionW) * stretch
        return VStack(spacing: 4) {
            ZStack(alignment: fromLeading ? .leading : .trailing) {
                RoundedRectangle(cornerRadius: SwipeTuning.actionRadius, style: .continuous)
                    .fill(a.tint)
                    .frame(width: plateWidth, height: actionH)
                // Иконка держится у дальнего от строки края плашки.
                glyph(a).frame(width: actionW, height: actionH)
            }
            .frame(width: actionW, height: actionH,
                   alignment: fromLeading ? .leading : .trailing)

            Text(a.title)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(palette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .opacity(Double(1 - stretch))
        }
        .frame(width: slotW)
        .modifier(GrowIn(progress: reveal))
        .contentShape(Rectangle())
        .highPriorityGesture(TapGesture().onEnded { closeThenPerform(a) })
    }

    private func glyph(_ a: RowAction) -> some View {
        Image(systemName: a.icon)
            .font(.system(size: 24, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: actionW, height: actionH)
    }

    // MARK: - Жест

    private var drag: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .local)
            .updating($gestureActive) { _, state, _ in state = true }
            .onChanged { value in
                if horizontal == nil {
                    horizontal = abs(value.translation.width) > abs(value.translation.height)
                    dragStart = offset
                    lockedSide = dragStart == 0 ? 0 : (dragStart > 0 ? 1 : -1)
                }
                guard horizontal == true else { return }
                // Только при смене: запись на каждом кадре дёргала весь список.
                if openRow != rowId { openRow = rowId }

                let wasStretched = stretchLeading >= 1 || stretchTrailing >= 1
                // Считаем от текущего положения строки, иначе обратный свайп
                // по открытой строке телепортирует её к нулю.
                let raw = dragStart + value.translation.width
                if raw > 0 {
                    offset = leading.isEmpty ? 0 : resisted(raw, limit: leadingWidth)
                } else {
                    offset = trailing.isEmpty ? 0 : -resisted(-raw, limit: trailingWidth)
                }
                // Сторона выбирается один раз за жест и дальше не меняется.
                if lockedSide == 0, offset != 0 { lockedSide = offset > 0 ? 1 : -1 }
                if lockedSide > 0 { offset = max(0, offset) }
                if lockedSide < 0 { offset = min(0, offset) }

                let nowStretched = stretchLeading >= 1 || stretchTrailing >= 1
                if nowStretched != wasStretched {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                }
            }
            .onEnded { value in
                defer { horizontal = nil; lockedSide = 0 }
                guard horizontal == true else { return }

                let vClose = velocity(value, to: 0)
                if stretchLeading >= 1, let a = leading.first { closeThenPerform(a, velocity: vClose); return }
                if stretchTrailing >= 1, let a = trailing.last { closeThenPerform(a, velocity: vClose); return }

                // Строка была открыта: закрываем только по реальному возврату,
                // иначе возвращаем туда, откуда взяли.
                if dragStart != 0 {
                    let closing = abs(offset) < abs(dragStart) * SwipeTuning.closeThreshold
                    if closing {
                        close(velocity: vClose)
                    } else {
                        withAnimation(spring(.open, velocity(value, to: dragStart))) {
                            offset = dragStart
                        }
                    }
                    return
                }

                let predicted = offset + (value.predictedEndTranslation.width - value.translation.width)
                if predicted > actionW * 0.6, !leading.isEmpty {
                    withAnimation(spring(.open, velocity(value, to: leadingWidth))) {
                        offset = leadingWidth
                    }
                } else if predicted < -actionW * 0.6, !trailing.isEmpty {
                    withAnimation(spring(.open, velocity(value, to: -trailingWidth))) {
                        offset = -trailingWidth
                    }
                } else {
                    close(velocity: vClose)
                }
            }
    }

    // MARK: - Подключение жеста

    /// Ход за пределом открытого состояния — вязкий и КОНЕЧНЫЙ: дальше порога
    /// срабатывания остаётся всего несколько десятков точек, потом строка
    /// упирается. Иначе её можно утащить на пол-экрана, и возврат с такого
    /// расстояния выглядит криво.
    private func resisted(_ value: CGFloat, limit: CGFloat) -> CGFloat {
        guard value > limit else { return value }
        let maxTravel = limit + CGFloat(appearance.swipeStretch) + SwipeTuning.overshootLimit
        return min(limit + (value - limit) * CGFloat(appearance.swipeResistance), maxTravel)
    }

    /// Сначала даём строке закрыться, потом выполняем действие: если менять
    /// порядок чатов сразу, переезд строки обрывает анимацию возврата и она
    /// выглядит скачком.
    private func closeThenPerform(_ a: RowAction, velocity: Double = 0) {
        close(velocity: velocity)
        // Ждём, пока возврат реально доиграет: раньше действие срабатывало
        // через 0.22с, на середине более мягкой пружины, и переезд строки
        // обрывал закрытие.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { a.perform() }
    }

    /// Переводит «предсказанный доезд» жеста в начальную скорость пружины:
    /// доли пути до цели за секунду.
    private func velocity(_ value: DragGesture.Value, to target: CGFloat) -> Double {
        let distance = abs(target - offset)
        guard distance > 1 else { return 0 }
        let predicted = value.predictedEndTranslation.width - value.translation.width
        return Double(predicted / distance)
    }

    private enum SpringKind { case open, close }

    /// Пружина собирается из настроек (Настройки → Экспериментальное), чтобы
    /// подбирать ощущение на устройстве, а не пересобирать приложение.
    private func spring(_ kind: SpringKind, _ v: Double) -> Animation {
        let duration = kind == .open ? appearance.swipeOpenDuration : appearance.swipeCloseDuration
        let bounce = kind == .open ? appearance.swipeOpenBounce : appearance.swipeCloseBounce
        return .interpolatingSpring(duration: duration, bounce: bounce,
                                    initialVelocity: appearance.swipeVelocityEnabled ? v : 0)
    }

    // MARK: - Обработка UIKit-жеста

    fileprivate func panChanged(_ dx: CGFloat) {
        if horizontal != true {
            horizontal = true
            dragStart = offset
            lockedSide = dragStart == 0 ? 0 : (dragStart > 0 ? 1 : -1)
        }
        if openRow != rowId { openRow = rowId }

        let wasStretched = stretchLeading >= 1 || stretchTrailing >= 1
        let raw = dragStart + dx
        if raw > 0 {
            offset = leading.isEmpty ? 0 : resisted(raw, limit: leadingWidth)
        } else {
            offset = trailing.isEmpty ? 0 : -resisted(-raw, limit: trailingWidth)
        }
        if lockedSide == 0, offset != 0 { lockedSide = offset > 0 ? 1 : -1 }
        if lockedSide > 0 { offset = max(0, offset) }
        if lockedSide < 0 { offset = min(0, offset) }
        // Целые точки: на дробных текст строки пересобирается с субпиксельным
        // сглаживанием и заметно дрожит во время протяжки.
        offset = offset.rounded()

        if (stretchLeading >= 1 || stretchTrailing >= 1) != wasStretched {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
    }

    fileprivate func panEnded(translation: CGFloat, velocity vx: CGFloat) {
        defer { horizontal = nil; lockedSide = 0 }
        // Скорость пальца (точки в секунду) → доли пути до цели в секунду.
        func v(to target: CGFloat) -> Double {
            let distance = abs(target - offset)
            return distance > 1 ? Double(vx / distance) : 0
        }
        if stretchLeading >= 1, let a = leading.first { closeThenPerform(a, velocity: v(to: 0)); return }
        if stretchTrailing >= 1, let a = trailing.last { closeThenPerform(a, velocity: v(to: 0)); return }

        if dragStart != 0 {
            let closing = abs(offset) < abs(dragStart) * SwipeTuning.closeThreshold
            if closing { close(velocity: v(to: 0)) } else {
                withAnimation(spring(.open, v(to: dragStart))) { offset = dragStart }
            }
            return
        }
        // Доводим по скорости: 0.15с прогноза достаточно, чтобы понять намерение.
        let predicted = offset + vx * 0.15
        if predicted > actionW * 0.6, !leading.isEmpty {
            withAnimation(spring(.open, v(to: leadingWidth))) { offset = leadingWidth }
        } else if predicted < -actionW * 0.6, !trailing.isEmpty {
            withAnimation(spring(.open, v(to: -trailingWidth))) { offset = -trailingWidth }
        } else {
            close(velocity: v(to: 0))
        }
    }

    private func close(velocity: Double = 0) {
        withAnimation(spring(.close, velocity)) { offset = 0 }
        if openRow == rowId { openRow = nil }
    }
}

/// Кнопка не появляется целиком, а прорастает от 0.3 масштаба — так свайп
/// читается плавным с первого миллиметра. Вынесен из SwipeRow: там имя Content
/// уже занято параметром самой строки.
private struct GrowIn: ViewModifier {
    var progress: CGFloat
    func body(content: Content) -> some View {
        content
            .scaleEffect(0.3 + 0.7 * progress)
            .opacity(Double(min(1, progress * 1.6)))
    }
}

/// Панорамирование, которое СДАЁТСЯ на вертикальном движении. Без этого
/// SwiftUI-жест выигрывает у прокрутки: любой сдвиг пальцем убивал скролл,
/// а на каждой строке висел свой распознаватель — отсюда и падение кадров.
final class HorizontalPanRecognizer: UIPanGestureRecognizer {
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesMoved(touches, with: event)
        guard state == .possible || state == .began else { return }
        let t = translation(in: view)
        // Вертикаль — не наше дело, отдаём прокрутке.
        if abs(t.y) > abs(t.x) { state = .failed }
    }
}

@available(iOS 18.0, *)
struct HorizontalPan: UIGestureRecognizerRepresentable {
    var onChanged: (CGFloat) -> Void
    var onEnded: (CGFloat, CGFloat) -> Void

    func makeUIGestureRecognizer(context: Context) -> HorizontalPanRecognizer {
        HorizontalPanRecognizer()
    }

    func handleUIGestureRecognizerAction(_ recognizer: HorizontalPanRecognizer, context: Context) {
        let t = recognizer.translation(in: recognizer.view)
        switch recognizer.state {
        case .changed:
            onChanged(t.x)
        case .ended, .cancelled, .failed:
            onEnded(t.x, recognizer.velocity(in: recognizer.view).x)
        default:
            break
        }
    }
}


/// Подключение жеста. На iOS 18+ берём UIKit-распознаватель: он отказывается от
/// вертикали и не спорит с прокруткой. На 17 остаётся SwiftUI-жест.
/// Вынесен из SwipeRow: там имя Content занято параметром самой строки.
private struct PanAttach: ViewModifier {
    var enabled: Bool = true
    var onChanged: (CGFloat) -> Void
    var onEnded: (CGFloat, CGFloat) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if !enabled {
            content
        } else if #available(iOS 18.0, *) {
            content.gesture(HorizontalPan(onChanged: onChanged, onEnded: onEnded))
        } else {
            content.gesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { onChanged($0.translation.width) }
                    .onEnded {
                        let predicted = $0.predictedEndTranslation.width - $0.translation.width
                        onEnded($0.translation.width, predicted / 0.15)
                    }
            )
        }
    }
}


