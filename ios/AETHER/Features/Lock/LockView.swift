import SwiftUI

// Экран блокировки: фирменный замок (лого — корпус, сверху дужка), Face ID
// (автозапуск) и стеклянный PIN-пад. При успехе дужка «отстёгивается», замок
// подпрыгивает, и весь экран улетает вверх (transition в RootView).
struct LockView: View {
    @ObservedObject var lock: AppLock
    @Environment(\.palette) private var palette

    @State private var pin = ""
    @State private var shake = false
    @State private var unlocked = false
    private let pinLength = 4

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            EdgeDim(edge: .top)
                .frame(height: 260)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                BrandLock(size: 96, open: unlocked)

                Text("Æther")
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(palette.textPrimary)
                    .padding(.top, 14)

                Text(unlocked ? "Разблокировано" : "Заблокировано")
                    .font(.subheadline)
                    .foregroundStyle(palette.textSecondary)
                    .padding(.top, 4)
                    .contentTransition(.opacity)

                // Индикатор PIN.
                HStack(spacing: 14) {
                    ForEach(0..<pinLength, id: \.self) { index in
                        Circle()
                            .fill(index < pin.count ? palette.accent : palette.surfaceElevated)
                            .frame(width: 14, height: 14)
                    }
                }
                .padding(.top, 22)
                .offset(x: shake ? -10 : 0)
                .animation(shake ? .spring(response: 0.1, dampingFraction: 0.15) : .default, value: shake)

                Spacer()

                // Face ID — над цифрами, ближе к замку.
                if lock.biometryAvailable {
                    Button {
                        Task { if await lock.tryBiometrics() { await performUnlock() } }
                    } label: {
                        Label(lock.biometryType == .faceID ? "Face ID" : "Touch ID",
                              systemImage: lock.biometryType == .faceID ? "faceid" : "touchid")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(palette.accent)
                            .padding(.horizontal, 18).padding(.vertical, 10)
                            .liquidGlass(Capsule())
                    }
                    .buttonStyle(.squish)
                    .padding(.bottom, 20)
                }

                pinPad

                Spacer().frame(height: 40)
            }
        }
        .task {
            // Автозапуск биометрии при показе экрана.
            if await lock.tryBiometrics() { await performUnlock() }
        }
    }

    /// Единая точка успеха: дужка открывается, короткая пауза — и RootView
    /// уводит экран вверх через finishUnlock().
    private func performUnlock() async {
        guard !unlocked else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        withAnimation(.spring(response: 0.4, dampingFraction: 0.62)) { unlocked = true }
        try? await Task.sleep(nanoseconds: 520_000_000)
        lock.finishUnlock()
    }

    private var pinPad: some View {
        VStack(spacing: 14) {
            ForEach(0..<3, id: \.self) { row in
                HStack(spacing: 22) {
                    ForEach(1...3, id: \.self) { col in
                        padButton("\(row * 3 + col)")
                    }
                }
            }
            HStack(spacing: 22) {
                Color.clear.frame(width: 76, height: 76)
                padButton("0")
                Button {
                    guard !pin.isEmpty else { return }
                    pin.removeLast()
                } label: {
                    Image(systemName: "delete.left")
                        .font(.system(size: 22))
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 76, height: 76)
                        .contentShape(Circle())
                }
                .buttonStyle(.squish)
            }
        }
    }

    private func padButton(_ digit: String) -> some View {
        Button {
            guard pin.count < pinLength else { return }
            pin.append(digit)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            if pin.count == pinLength {
                if lock.tryPin(pin) {
                    Task { await performUnlock() }
                } else {
                    // Неверный PIN: встряска и сброс.
                    UINotificationFeedbackGenerator().notificationOccurred(.error)
                    shake = true
                    Task {
                        try? await Task.sleep(nanoseconds: 350_000_000)
                        shake = false
                        pin = ""
                    }
                }
            }
        } label: {
            Text(digit)
                .font(.system(size: 30, weight: .medium, design: .rounded))
                .foregroundStyle(palette.textPrimary)
                .frame(width: 76, height: 76)
                .liquidGlass(Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.squish)
    }
}

// Экран задания PIN при включении блокировки (два ввода с подтверждением).
struct PinSetupView: View {
    var onDone: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    @State private var first = ""
    @State private var pin = ""
    @State private var stage = 0   // 0 — ввод, 1 — подтверждение
    @State private var mismatch = false
    private let pinLength = 4

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(spacing: 0) {
                Spacer()
                Text(stage == 0 ? "Придумай PIN" : "Повтори PIN")
                    .font(.headline)
                    .foregroundStyle(palette.textPrimary)
                if mismatch {
                    Text("PIN не совпал, попробуй ещё раз")
                        .font(.caption)
                        .foregroundStyle(palette.danger)
                        .padding(.top, 6)
                }
                HStack(spacing: 14) {
                    ForEach(0..<pinLength, id: \.self) { index in
                        Circle()
                            .fill(index < pin.count ? palette.accent : palette.surfaceElevated)
                            .frame(width: 14, height: 14)
                    }
                }
                .padding(.top, 24)
                Spacer()
                pad
                Button("Отмена") { dismiss() }
                    .foregroundStyle(palette.textSecondary)
                    .padding(.top, 18)
                Spacer().frame(height: 40)
            }
        }
    }

    private var pad: some View {
        VStack(spacing: 14) {
            ForEach(0..<3, id: \.self) { row in
                HStack(spacing: 22) {
                    ForEach(1...3, id: \.self) { col in
                        digitButton("\(row * 3 + col)")
                    }
                }
            }
            HStack(spacing: 22) {
                Color.clear.frame(width: 76, height: 76)
                digitButton("0")
                Button {
                    guard !pin.isEmpty else { return }
                    pin.removeLast()
                } label: {
                    Image(systemName: "delete.left")
                        .font(.system(size: 22))
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 76, height: 76)
                        .contentShape(Circle())
                }
                .buttonStyle(.squish)
            }
        }
    }

    private func digitButton(_ digit: String) -> some View {
        Button {
            guard pin.count < pinLength else { return }
            pin.append(digit)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            guard pin.count == pinLength else { return }
            if stage == 0 {
                first = pin
                pin = ""
                stage = 1
                mismatch = false
            } else if pin == first {
                onDone(pin)
                dismiss()
            } else {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                mismatch = true
                pin = ""
                first = ""
                stage = 0
            }
        } label: {
            Text(digit)
                .font(.system(size: 30, weight: .medium, design: .rounded))
                .foregroundStyle(palette.textPrimary)
                .frame(width: 76, height: 76)
                .liquidGlass(Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.squish)
    }
}
