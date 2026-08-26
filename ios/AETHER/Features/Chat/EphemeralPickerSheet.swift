import SwiftUI

// Выбор режима для СЛЕДУЮЩЕГО сообщения.
//
// Режим разовый: после отправки сбрасывается. Иначе «одноразовое» осталось бы
// включённым на всю переписку, и человек однажды отправил бы так то, что
// хотел сохранить.
struct EphemeralPickerSheet: View {
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    private static let presets: [(seconds: Int64, title: String)] = [
        (10, "10 секунд"),
        (30, "30 секунд"),
        (60, "1 минута"),
        (300, "5 минут"),
        (3600, "1 час"),
        (86_400, "24 часа"),
        (604_800, "7 дней"),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { pick(nil) } label: {
                        modeRow("Обычное сообщение", subtitle: "Остаётся в переписке",
                                icon: "bubble.left", selected: messaging.pendingEphemeral == nil)
                    }
                    .listRowBackground(palette.surface)

                    Button { pickViewOnce() } label: {
                        modeRow("Просмотр один раз",
                                subtitle: "После просмотра станет недоступно",
                                icon: "eye",
                                selected: messaging.pendingEphemeral?.kind == "VIEW_ONCE")
                    }
                    .listRowBackground(palette.surface)
                }

                Section {
                    ForEach(Self.presets, id: \.seconds) { preset in
                        Button { pickTimer(preset.seconds) } label: {
                            HStack {
                                Text(preset.title).foregroundStyle(palette.textPrimary)
                                Spacer()
                                if isSelectedTimer(preset.seconds) {
                                    Image(systemName: "checkmark").foregroundStyle(palette.accent)
                                }
                            }
                        }
                        .listRowBackground(palette.surface)
                    }
                } header: {
                    Text("Исчезнет после открытия")
                } footer: {
                    Text("Отсчёт начинается, когда получатель откроет сообщение. Содержимое стирается с обоих устройств, включая вложения.\n\nAether не может помешать переписать текст рукой или снять экран другим телефоном — и не обещает этого.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(palette.background.ignoresSafeArea())
            .navigationTitle("Режим сообщения")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Готово") { dismiss() } }
            }
        }
    }

    private func modeRow(_ title: String, subtitle: String, icon: String, selected: Bool) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.accent).frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(palette.textPrimary)
                Text(subtitle).font(.caption).foregroundStyle(palette.textSecondary)
            }
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(palette.accent) }
        }
    }

    private func isSelectedTimer(_ seconds: Int64) -> Bool {
        guard let spec = messaging.pendingEphemeral, spec.kind == "EPHEMERAL" else { return false }
        return spec.ttlSeconds == seconds
    }

    private func pick(_ spec: EphemeralSpec?) {
        messaging.pendingEphemeral = spec
        dismiss()
    }

    private func pickViewOnce() {
        pick(EphemeralSpec(kind: "VIEW_ONCE", ttlSeconds: 0,
                           trigger: .firstOpen, absoluteMs: nil, viewLimit: 1))
    }

    private func pickTimer(_ seconds: Int64) {
        pick(EphemeralSpec(kind: "EPHEMERAL", ttlSeconds: seconds,
                           trigger: .firstOpen, absoluteMs: nil, viewLimit: nil))
    }
}
