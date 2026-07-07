import SwiftUI

/// Стартовый экран AETHER. Знак, название, слоган и стеклянная кнопка входа.
/// Весь хром — Liquid Glass поверх near-black фона.
struct WelcomeView: View {
    @Environment(Session.self) private var session
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            AetherLogo(size: 132)
                .scaleEffect(appeared ? 1 : 0.8)
                .opacity(appeared ? 1 : 0)

            Text("AETHER")
                .font(.aetherDisplay(44))
                .tracking(14)
                .foregroundStyle(Brand.textPrimary)
                .padding(.top, 28)
                .padding(.leading, 14) // компенсируем трекинг справа
                .opacity(appeared ? 1 : 0)

            Text("Защищённое пространство.\nСквозное шифрование. Ничего лишнего.")
                .font(.system(size: 15, weight: .regular))
                .multilineTextAlignment(.center)
                .foregroundStyle(Brand.textSecondary)
                .lineSpacing(4)
                .padding(.top, 14)
                .opacity(appeared ? 1 : 0)

            Spacer()

            GlassGroup(spacing: 14) {
                VStack(spacing: 14) {
                    Button {
                        session.beginAuth()
                    } label: {
                        Text("Начать")
                    }
                    .buttonStyle(.glassProminent)

                    HStack(spacing: 8) {
                        Image(systemName: "lock.shield")
                            .font(.system(size: 13))
                        Text("End-to-end · открытый протокол")
                            .font(.system(size: 13))
                    }
                    .foregroundStyle(Brand.textMuted)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity)
                    .liquidGlass(in: Capsule())
                }
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 24)
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 24)
        }
        .onAppear {
            withAnimation(.spring(duration: 0.8)) { appeared = true }
        }
    }
}

#Preview {
    ZStack { Brand.backdrop.ignoresSafeArea(); WelcomeView() }
        .environment(Session())
}
