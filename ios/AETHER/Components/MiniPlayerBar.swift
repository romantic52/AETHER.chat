import SwiftUI

/// Полоска играющего трека поверх приложения. Появляется, когда музыка или
/// голосовое запущены из просмотрщика, и не исчезает при уходе с экрана — иначе
/// звук идёт, а управлять им негде, кроме экрана блокировки.
///
/// Живёт сверху, а не над таб-баром: внизу в чате стоит поле ввода, и полоска
/// перекрывала бы его ровно там, где чаще всего слушают.
struct MiniPlayerBar: View {
    @ObservedObject private var center = MediaPlaybackCenter.shared
    @Environment(\.palette) private var palette
    @State private var showFull = false

    var body: some View {
        if let track = center.track {
            HStack(spacing: 10) {
                Image(systemName: "waveform")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(palette.accent)
                    .frame(width: 22)

                VStack(alignment: .leading, spacing: 1) {
                    Text(track.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .lineLimit(1)
                    Text(track.subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(palette.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button { center.toggle() } label: {
                    Image(systemName: center.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(palette.textPrimary)
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)

                Button { center.stop() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(alignment: .bottom) { progressLine }
            .liquidGlass(Capsule())
            .padding(.horizontal, 16)
            .padding(.bottom, 6)
            .contentShape(Capsule())
            .onTapGesture { showFull = true }
            .fullScreenCover(isPresented: $showFull) {
                if let payload = center.currentPayload { AetherMediaViewer(payload: payload) }
            }
            .transition(.move(edge: .top).combined(with: .opacity))
            .animation(.easeInOut(duration: 0.22), value: center.track)
        }
    }

    /// Тонкая полоса прогресса по низу капсулы: время отдельными цифрами здесь
    /// не поместится, а знать, сколько осталось, всё равно нужно.
    private var progressLine: some View {
        GeometryReader { geo in
            let done = center.duration > 0 ? center.current / center.duration : 0
            Capsule()
                .fill(palette.accent.opacity(0.85))
                .frame(width: geo.size.width * done, height: 2)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
    }
}
