import SwiftUI

struct SettingsView: View {
    @Environment(Session.self) private var session

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack {
                    Text("Настройки")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Brand.textPrimary)
                    Spacer()
                }
                .padding(.top, 6)

                profileCard

                GlassGroup(spacing: 10) {
                    VStack(spacing: 10) {
                        SettingsRow(icon: "lock.shield", title: "Приватность и безопасность")
                        SettingsRow(icon: "bell", title: "Уведомления")
                        SettingsRow(icon: "paintbrush", title: "Оформление")
                        SettingsRow(icon: "key", title: "Ключи и safety number")
                    }
                }

                Button { session.logout() } label: {
                    Text("Выйти")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .liquidGlass(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 110)
        }
    }

    private var profileCard: some View {
        HStack(spacing: 14) {
            Avatar(name: session.userId.isEmpty ? "A" : session.userId, size: 60)
            VStack(alignment: .leading, spacing: 3) {
                Text(session.userId.isEmpty ? "AETHER" : session.userId)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Brand.textPrimary)
                Text(session.server)
                    .font(.system(size: 13))
                    .foregroundStyle(Brand.textMuted)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(16)
        .glassCard(cornerRadius: 22)
    }
}

private struct SettingsRow: View {
    var icon: String
    var title: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundStyle(Brand.textSecondary)
                .frame(width: 24)
            Text(title).font(.system(size: 16)).foregroundStyle(Brand.textPrimary)
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 13)).foregroundStyle(Brand.textMuted)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .glassCard(cornerRadius: 16)
    }
}
