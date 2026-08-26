import SwiftUI

// Приватность обнаружения: четыре независимые оси.
//
// Видимость и разрешение — разные вещи. Можно быть видимым всем и при этом
// запрещать писать; можно разрешить открыть профиль, но не звонить.
// Один флаг «виден/невиден» такие сочетания выразить не может.
struct NearbyPrivacyView: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var privacy = NearbyPrivacy.shared

    var body: some View {
        List {
            Section {
                Toggle(isOn: $privacy.enabled) {
                    SettingsLabel("Обнаружение рядом", icon: "dot.radiowaves.left.and.right", color: .blue)
                }
                Toggle(isOn: $privacy.bluetoothVisible) {
                    SettingsLabel("Через Bluetooth", icon: "wave.3.right", color: .indigo)
                }
                .disabled(!privacy.enabled)
                Toggle(isOn: $privacy.networkVisible) {
                    SettingsLabel("Через сеть", icon: "globe", color: .teal)
                }
                .disabled(!privacy.enabled)
            } footer: {
                Text("Bluetooth ищет тех, кто физически рядом, и работает, пока приложение открыто. Обнаружение через сеть появится отдельным этапом и требует отдельного согласия на передачу примерного местоположения.")
            }
            .listRowBackground(palette.surface)

            Section {
                ForEach(NearbyAudience.allCases, id: \.rawValue) { option in
                    Button { privacy.audience = option } label: {
                        HStack {
                            Text(option.title).foregroundStyle(palette.textPrimary)
                            Spacer()
                            if privacy.audience == option {
                                Image(systemName: "checkmark").foregroundStyle(palette.accent)
                            }
                        }
                    }
                }
            } header: {
                Text("Кто может обнаружить меня")
            } footer: {
                Text("В эфир уходит только идентификатор, который меняется каждые 15 минут. Ни имени, ни логина, ни постоянного номера там нет — узнать вас может лишь тот, с кем вы обменялись ключом обнаружения.")
            }
            .listRowBackground(palette.surface)

            Section {
                ForEach(StrangerVisibility.allCases, id: \.rawValue) { option in
                    Button { privacy.strangerVisibility = option } label: {
                        HStack {
                            Text(option.title).foregroundStyle(palette.textPrimary)
                            Spacer()
                            if privacy.strangerVisibility == option {
                                Image(systemName: "checkmark").foregroundStyle(palette.accent)
                            }
                        }
                    }
                }
            } header: {
                Text("Что видят незнакомые")
            }
            .listRowBackground(palette.surface)

            Section {
                Toggle("Открыть мой профиль", isOn: $privacy.strangersCanOpenProfile)
                Toggle("Открыть чат", isOn: $privacy.strangersCanOpenChat)
                Toggle("Сразу написать", isOn: $privacy.strangersCanMessage)
                Toggle("Прислать файл", isOn: $privacy.strangersCanSendFile)
                Toggle("Позвонить", isOn: $privacy.strangersCanCall)
            } header: {
                Text("Что незнакомец может сделать")
            } footer: {
                Text("Быть видимым и разрешать действия — разные вещи. Можно оставаться заметным, но запретить писать.")
            }
            .listRowBackground(palette.surface)

            Section {
                ForEach([(900.0, "15 минут"), (3600.0, "1 час"), (28800.0, "8 часов")], id: \.0) { item in
                    Button(item.1) { privacy.makeVisible(for: item.0) }
                        .foregroundStyle(palette.accent)
                }
                Button("Пока не выключу") { privacy.makeVisible(for: nil) }
                    .foregroundStyle(palette.accent)
            } header: {
                Text("Стать видимым всем на время")
            } footer: {
                Text("По истечении срока видимость отключится сама.")
            }
            .listRowBackground(palette.surface)
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle("Кто меня видит")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { Button("Готово") { dismiss() } }
        }
    }
}
