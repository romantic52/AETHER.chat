import SwiftUI
import WebRTC

// Полноэкранный звонок: видео (remote во весь экран + local PiP) или аудио (аватар),
// с входящим/исходящим состоянием и панелью управления на стекле.
struct CallView: View {
    @ObservedObject var call: CallManager
    @Environment(\.palette) private var palette

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if call.isVideo && call.state == .active {
                RemoteVideoView(call: call).ignoresSafeArea()
                localPiP
            } else {
                audioBackdrop
            }

            VStack {
                topInfo
                Spacer()
                controls
            }
            .padding()
        }
        .preferredColorScheme(.dark)
    }

    private var topInfo: some View {
        VStack(spacing: 8) {
            if !(call.isVideo && call.state == .active) {
                Avatar(id: call.peerId, name: call.peerId, size: 110)
                    .padding(.top, 40)
            }
            Text(call.peerId)
                .font(.title2.weight(.semibold)).foregroundStyle(.white)
                .shadow(radius: 4)
            Text(call.endMessage ?? statusText)
                .font(.subheadline).foregroundStyle(.white.opacity(0.85))
                .shadow(radius: 4)
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

    @ViewBuilder private var controls: some View {
        if call.state == .incoming {
            HStack(spacing: 70) {
                circleButton("phone.down.fill", .red) { call.decline() }
                circleButton("phone.fill", .green) { call.accept() }
            }
            .padding(.bottom, 40)
        } else if call.state == .ended {
            EmptyView()
        } else {
            HStack(spacing: 14) {
                toggleButton(call.micOn ? "mic.fill" : "mic.slash.fill", active: call.micOn) { call.toggleMic() }
                if call.isVideo {
                    toggleButton(call.cameraOn ? "video.fill" : "video.slash.fill", active: call.cameraOn) { call.toggleCamera() }
                    toggleButton("arrow.triangle.2.circlepath.camera", active: true) { call.switchCamera() }
                }
                toggleButton(call.speakerOn ? "speaker.wave.2.fill" : "speaker.fill", active: call.speakerOn) { call.toggleSpeaker() }
                circleButton("phone.down.fill", .red) { call.hangup() }
            }
            .padding(.bottom, 40)
        }
    }

    private var localPiP: some View {
        VStack {
            HStack {
                Spacer()
                LocalVideoView(call: call)
                    .frame(width: 110, height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: Radius.card).stroke(.white.opacity(0.3), lineWidth: 1))
                    .padding(.top, 60).padding(.trailing, 16)
            }
            Spacer()
        }
    }

    private var audioBackdrop: some View {
        LinearGradient(colors: [palette.accent.opacity(0.35), .black], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    private func circleButton(_ icon: String, _ color: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white).frame(width: 68, height: 68).background(color, in: Circle())
        }.buttonStyle(.squish)
    }

    private func toggleButton(_ icon: String, active: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 22, weight: .semibold))
                .foregroundStyle(active ? .white : .black)
                .frame(width: 58, height: 58)
                .background(active ? AnyShapeStyle(.ultraThinMaterial) : AnyShapeStyle(Color.white), in: Circle())
        }.buttonStyle(.squish)
    }

    private func timeString(_ t: TimeInterval) -> String {
        String(format: "%02d:%02d", Int(t) / 60, Int(t) % 60)
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
