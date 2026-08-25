import SwiftUI
import WebRTC

// Полноэкранный звонок: видео (remote во весь экран + local PiP) или аудио (аватар)
// поверх живого градиента, который меняет цвет по состоянию звонка.
// Управление — стеклянный поднос, для видео дополнительно панель масок и жестов.
struct CallView: View {
    @ObservedObject var call: CallManager
    @Environment(\.palette) private var palette
    @EnvironmentObject private var appearance: AppearanceSettings

    @State private var showEffects = false

    private var mood: CallMood { CallMood.from(state: call.state, result: call.lastResult) }
    private var isRinging: Bool {
        call.state == .dialing || call.state == .incoming || call.state == .connecting || call.state == .preparing
    }
    private var showsRemoteVideo: Bool { call.isVideo && call.state == .active }
    /// Поверх видео всегда белый текст, поверх градиента — цвет темы.
    private var contentColor: Color { showsRemoteVideo ? .white : palette.textPrimary }

    var body: some View {
        ZStack {
            CallBackdrop(mood: mood, pulsing: isRinging)

            if showsRemoteVideo {
                RemoteVideoView(call: call)
                    .ignoresSafeArea()
                    .transition(.opacity)
                localPiP
            }

            VStack(spacing: 0) {
                header
                Spacer(minLength: 16)
                controlsStack
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 26)

            SignFlash(effects: call.effects)
        }
        .animation(.easeInOut(duration: 0.35), value: call.state)
        .animation(.spring(response: 0.42, dampingFraction: 0.86), value: showEffects)
    }

    // MARK: - Шапка

    private var header: some View {
        VStack(spacing: 12) {
            if !showsRemoteVideo {
                ZStack {
                    CallPulse(color: moodTint, active: isRinging, size: 132)
                    Avatar(id: call.peerId, name: call.peerId, size: 124)
                        .shadow(color: moodTint.opacity(0.45), radius: 26, y: 10)
                }
                .padding(.top, 52)
            }

            Text(call.peerId)
                .font(.system(size: 28, weight: .semibold, design: .rounded))
                .foregroundStyle(contentColor)
                .shadow(color: .black.opacity(showsRemoteVideo ? 0.5 : 0), radius: 6)

            statusChip
        }
        .padding(.top, showsRemoteVideo ? 54 : 0)
    }

    private var statusChip: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(moodTint)
                .frame(width: 7, height: 7)
                .opacity(isRinging ? 0.35 : 1)
                .scaleEffect(isRinging ? 1.5 : 1)
                .animation(isRinging ? .easeInOut(duration: 0.7).repeatForever(autoreverses: true) : .default,
                           value: isRinging)

            Text(call.endMessage ?? statusText)
                .font(call.state == .active
                      ? .system(size: 15, weight: .medium, design: .monospaced)
                      : .system(size: 15, weight: .medium))
                .foregroundStyle(contentColor.opacity(0.9))
                .contentTransition(.numericText())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .liquidGlass(Capsule(style: .continuous), neutral: true)
    }

    private var moodTint: Color {
        switch mood {
        case .neutral: return isRinging ? palette.accent : palette.textSecondary
        case .connected: return palette.readTick
        case .failed: return palette.danger
        }
    }

    private var statusText: String {
        switch call.state {
        case .preparing: return "Подготовка…"
        case .dialing: return "Вызов…"
        case .incoming: return call.isVideo ? "Входящий видеозвонок" : "Входящий звонок"
        case .connecting: return "Соединение…"
        case .active: return timeString(call.duration)
        case .ended: return "Звонок завершён"
        case .idle: return ""
        }
    }

    // MARK: - Управление

    @ViewBuilder private var controlsStack: some View {
        VStack(spacing: 12) {
            if showEffects && call.isVideo && call.state != .incoming {
                EffectsTray(effects: call.effects)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if call.state == .incoming {
                incomingControls
            } else if call.state != .ended {
                activeControls
            }
        }
    }

    private var incomingControls: some View {
        HStack(spacing: 0) {
            answerButton(icon: "phone.down.fill", title: "Отклонить",
                         colors: [Palette.rgb(0xFF6B6B), Palette.rgb(0xD91E36)],
                         pulse: false) { call.decline() }
            Spacer()
            answerButton(icon: call.isVideo ? "video.fill" : "phone.fill", title: "Ответить",
                         colors: [Palette.rgb(0x4ADE80), Palette.rgb(0x15A34A)],
                         pulse: true) { call.accept() }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 18)
    }

    private var activeControls: some View {
        GlassGroup(spacing: 16) {
            VStack(spacing: 18) {
                HStack(spacing: 6) {
                    controlButton(call.micOn ? "mic.fill" : "mic.slash.fill",
                                  title: call.micOn ? "Микрофон" : "Без звука",
                                  on: call.micOn) { call.toggleMic() }

                    if call.isVideo {
                        controlButton(call.cameraOn ? "video.fill" : "video.slash.fill",
                                      title: "Камера", on: call.cameraOn) { call.toggleCamera() }
                        controlButton("arrow.triangle.2.circlepath", title: "Развернуть", on: true) {
                            call.switchCamera()
                        }
                        controlButton("sparkles", title: "Эффекты", on: showEffects || call.effects.mask.isActive) {
                            showEffects.toggle()
                        }
                    }

                    controlButton(call.speakerOn ? "speaker.wave.2.fill" : "speaker.fill",
                                  title: "Динамик", on: call.speakerOn) { call.toggleSpeaker() }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .liquidGlass(Capsule(style: .continuous), interactive: true, neutral: true)

                Button { call.hangup() } label: {
                    Image(systemName: "phone.down.fill")
                        .font(.system(size: 27, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 72, height: 72)
                        .background(
                            LinearGradient(colors: [Palette.rgb(0xFF6B6B), Palette.rgb(0xD91E36)],
                                           startPoint: .top, endPoint: .bottom),
                            in: Circle()
                        )
                        .shadow(color: Palette.rgb(0xD91E36).opacity(0.5), radius: 18, y: 8)
                }
                .buttonStyle(.squish)
            }
        }
    }

    private func controlButton(_ icon: String, title: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(on ? palette.textPrimary : palette.background)
                    .frame(width: 50, height: 50)
                    .background(Circle().fill(on
                                              ? AnyShapeStyle(palette.textPrimary.opacity(0.13))
                                              : AnyShapeStyle(palette.textPrimary.opacity(0.92))))
                Text(title)
                    .font(.system(size: 9.5, weight: .medium))
                    .foregroundStyle(palette.textSecondary)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(width: 62)
        }
        .buttonStyle(.squish)
    }

    private func answerButton(icon: String, title: String, colors: [Color],
                              pulse: Bool, _ action: @escaping () -> Void) -> some View {
        VStack(spacing: 10) {
            ZStack {
                CallPulse(color: colors[0], active: pulse, size: 84)
                Button(action: action) {
                    Image(systemName: icon)
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 82, height: 82)
                        .background(LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom), in: Circle())
                        .shadow(color: colors[1].opacity(0.55), radius: 22, y: 10)
                }
                .buttonStyle(.squish)
            }
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(contentColor.opacity(0.8))
        }
    }

    private var localPiP: some View {
        VStack {
            HStack {
                Spacer()
                LocalVideoView(call: call)
                    .frame(width: 112, height: 164)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                            .stroke(.white.opacity(0.28), lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.35), radius: 14, y: 6)
                    .padding(.top, 64).padding(.trailing, 16)
            }
            Spacer()
        }
    }

    private func timeString(_ t: TimeInterval) -> String {
        String(format: "%02d:%02d", Int(t) / 60, Int(t) % 60)
    }
}

// Панель масок и жестов: круглые чипы в капсуле и отдельная пилюля с жестами.
// Маска накладывается на кадр до отправки, так что собеседник видит то же самое.
private struct EffectsTray: View {
    @ObservedObject var effects: VideoEffects
    @Environment(\.palette) private var palette

    var body: some View {
        GlassGroup(spacing: 12) {
            VStack(spacing: 10) {
                HStack(spacing: 6) {
                    ForEach(CallMask.allCases) { mask in
                        chip(mask)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .liquidGlass(Capsule(style: .continuous), interactive: true, neutral: true)

                HStack(spacing: 10) {
                    Text(effects.mask.title)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                    Spacer(minLength: 8)
                    Text("✌️").font(.system(size: 14))
                    Toggle("Жесты", isOn: $effects.gesturesEnabled)
                        .labelsHidden()
                        .tint(palette.accent)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .liquidGlass(Capsule(style: .continuous), neutral: true)
            }
        }
    }

    private func chip(_ mask: CallMask) -> some View {
        let selected = effects.mask == mask
        return Button {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { effects.mask = mask }
        } label: {
            Text(mask.chip)
                .font(.system(size: 21))
                .frame(width: 44, height: 44)
                .background(Circle().fill(selected
                                          ? AnyShapeStyle(palette.accent.opacity(0.85))
                                          : AnyShapeStyle(palette.textPrimary.opacity(0.12))))
                .scaleEffect(selected ? 1.06 : 1)
        }
        .buttonStyle(.squish)
    }
}

// Локальная вспышка распознанного жеста поверх экрана (в кадр она попадает
// отдельно, из VideoEffects, чтобы её увидел и собеседник).
private struct SignFlash: View {
    @ObservedObject var effects: VideoEffects
    @Environment(\.palette) private var palette

    var body: some View {
        ZStack {
            if let sign = effects.sign {
                VStack(spacing: 8) {
                    Text(sign.emoji).font(.system(size: 86))
                    Text(sign.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .liquidGlass(Capsule(style: .continuous), neutral: true)
                }
                .transition(.scale(scale: 0.4).combined(with: .opacity))
                .offset(y: -40)
            }
        }
        .allowsHitTesting(false)
        .animation(.spring(response: 0.42, dampingFraction: 0.68), value: effects.sign)
    }
}

// RTCMTLVideoView-обёртки.
struct RemoteVideoView: UIViewRepresentable {
    @ObservedObject var call: CallManager
    final class Coordinator {
        weak var client: WebRTCClient?
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> RTCMTLVideoView {
        let v = RTCMTLVideoView(); v.videoContentMode = .scaleAspectFill
        context.coordinator.client = call.client
        call.client?.renderRemote(to: v)
        return v
    }
    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        if context.coordinator.client !== call.client {
            context.coordinator.client?.removeRemoteRenderer(uiView)
            context.coordinator.client = call.client
        }
        call.client?.renderRemote(to: uiView)
    }
    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: Coordinator) {
        coordinator.client?.removeRemoteRenderer(uiView)
    }
}

struct LocalVideoView: UIViewRepresentable {
    @ObservedObject var call: CallManager
    final class Coordinator {
        weak var client: WebRTCClient?
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> RTCMTLVideoView {
        let v = RTCMTLVideoView(); v.videoContentMode = .scaleAspectFill
        context.coordinator.client = call.client
        call.client?.renderLocal(to: v)
        return v
    }
    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        if context.coordinator.client !== call.client {
            context.coordinator.client?.removeLocalRenderer(uiView)
            context.coordinator.client = call.client
        }
        call.client?.renderLocal(to: uiView)
    }
    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: Coordinator) {
        coordinator.client?.removeLocalRenderer(uiView)
    }
}
