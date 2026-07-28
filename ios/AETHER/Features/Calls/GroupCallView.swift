import SwiftUI

// Экран группового звонка (аудио, mesh): сетка участников, длительность,
// микрофон/динамик/выход. Показывается оверлеем поверх всего (см. HomeView).
struct GroupCallView: View {
    @ObservedObject var call: GroupCallManager
    @EnvironmentObject var messaging: Messaging
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    private var groupName: String {
        messaging.groups.info(call.groupId)?.name ?? call.groupId
    }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            LinearGradient(colors: [palette.accent.opacity(0.25), .clear],
                           startPoint: .top, endPoint: .center)
                .ignoresSafeArea()

            VStack(spacing: 24) {
                VStack(spacing: 6) {
                    Text(groupName)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(palette.textPrimary)
                    Text(timeString(call.duration))
                        .font(.system(size: 15, design: .monospaced))
                        .foregroundStyle(palette.textSecondary)
                        .contentTransition(.numericText())
                }
                .padding(.top, 60)

                // Участники: я + подключённые пиры.
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 18)], spacing: 20) {
                    participantCell(id: session.myId.lowercased(), connected: true, isMe: true)
                    ForEach(call.peers.keys.sorted(), id: \.self) { peer in
                        participantCell(id: peer, connected: call.peers[peer] ?? false, isMe: false)
                    }
                }
                .padding(.horizontal, 28)

                if call.peers.isEmpty {
                    Text("Ожидание участников…")
                        .font(.subheadline)
                        .foregroundStyle(palette.textSecondary)
                }

                Spacer()

                // Контролы.
                HStack(spacing: 22) {
                    controlButton(icon: call.micOn ? "mic.fill" : "mic.slash.fill",
                                  active: call.micOn) { call.micOn.toggle() }
                    controlButton(icon: call.speakerOn ? "speaker.wave.2.fill" : "speaker.slash.fill",
                                  active: call.speakerOn) { call.speakerOn.toggle() }
                    Button {
                        call.leave()
                    } label: {
                        Image(systemName: "phone.down.fill")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 68, height: 68)
                            .background(palette.danger, in: Circle())
                    }
                    .buttonStyle(.squish)
                }
                .padding(.bottom, 48)
            }
        }
    }

    private func participantCell(id: String, connected: Bool, isMe: Bool) -> some View {
        VStack(spacing: 8) {
            Avatar(id: id, name: isMe ? String(localized: "Вы") : id, size: 84,
                   avatarURL: isMe ? session.myAvatarURL : messaging.avatarURL(id))
                .overlay(
                    Circle().stroke(connected ? palette.readTick : palette.textSecondary.opacity(0.4),
                                    lineWidth: 3)
                )
            Text(isMe ? String(localized: "Вы")
                      : messaging.displayName(id, fallback: id))
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(palette.textPrimary)
                .lineLimit(1)
            if !connected && !isMe {
                Text("подключение…")
                    .font(.caption2)
                    .foregroundStyle(palette.textSecondary)
            }
        }
    }

    private func controlButton(icon: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(active ? palette.textPrimary : palette.onAccent)
                .frame(width: 60, height: 60)
                .background(active ? AnyShapeStyle(.ultraThinMaterial)
                                   : AnyShapeStyle(palette.textSecondary), in: Circle())
        }
        .buttonStyle(.squish)
    }

    private func timeString(_ t: TimeInterval) -> String {
        String(format: "%d:%02d", Int(t) / 60, Int(t) % 60)
    }
}

// Баннер входящего приглашения в групповой звонок (поверх всего, как в Telegram).
struct GroupCallInviteBanner: View {
    @ObservedObject var call: GroupCallManager
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette

    var body: some View {
        if let invite = call.pendingInvite {
            VStack {
                HStack(spacing: 12) {
                    Image(systemName: "person.3.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(palette.accent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(messaging.groups.info(invite.groupId)?.name ?? invite.groupId)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(palette.textPrimary)
                            .lineLimit(1)
                        Text(String(format: String(localized: "Групповой звонок от %@"),
                                    messaging.displayName(invite.from, fallback: invite.from)))
                            .font(.system(size: 12))
                            .foregroundStyle(palette.textSecondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button { call.pendingInvite = nil } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(palette.textSecondary)
                            .frame(width: 34, height: 34)
                            .background(palette.surfaceElevated, in: Circle())
                    }
                    .buttonStyle(.squish)
                    Button { call.join(invite) } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(palette.readTick, in: Circle())
                    }
                    .buttonStyle(.squish)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .liquidGlass(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
            }
            .transition(.move(edge: .top).combined(with: .opacity))
            .zIndex(150)
        }
    }
}
