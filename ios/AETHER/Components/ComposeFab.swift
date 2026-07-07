import SwiftUI

/// Плавающая кнопка «написать» с фирменным морфингом Liquid Glass (iOS 26):
/// главная капля при тапе «вытекает» в кнопки-действия внутри одного
/// `GlassEffectContainer`, связанные `glassEffectID`. На iOS < 26 — обычное меню.
struct ComposeFab: View {
    var onNewChat: () -> Void
    var onNewGroup: () -> Void

    @State private var open = false
    @Namespace private var ns

    var body: some View {
        if #available(iOS 26.0, *) {
            morphing
        } else {
            fallback
        }
    }

    @available(iOS 26.0, *)
    private var morphing: some View {
        GlassEffectContainer(spacing: 18) {
            VStack(alignment: .trailing, spacing: 14) {
                if open {
                    action(icon: "person.2.fill", title: "Новая группа", id: "group") {
                        close(); onNewGroup()
                    }
                    action(icon: "square.and.pencil", title: "Сообщение", id: "msg") {
                        close(); onNewChat()
                    }
                }

                Button { toggle() } label: {
                    Image(systemName: open ? "xmark" : "square.and.pencil")
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(.black)
                        .frame(width: 58, height: 58)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.tint(.white).interactive(), in: Circle())
                .glassEffectID("fab", in: ns)
            }
        }
    }

    @available(iOS 26.0, *)
    private func action(icon: String, title: String, id: String, act: @escaping () -> Void) -> some View {
        Button(action: act) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.white)
                    .fixedSize()
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
            }
            .padding(.leading, 18)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: Capsule())
        .glassEffectID(id, in: ns)
    }

    private var fallback: some View {
        Menu {
            Button { onNewChat() } label: { Label("Сообщение", systemImage: "square.and.pencil") }
            Button { onNewGroup() } label: { Label("Новая группа", systemImage: "person.2.fill") }
        } label: {
            Image(systemName: "square.and.pencil")
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(.black)
                .frame(width: 58, height: 58)
                .background(Circle().fill(.white))
        }
    }

    private func toggle() { withAnimation(.spring(response: 0.42, dampingFraction: 0.78)) { open.toggle() } }
    private func close() { withAnimation(.spring(response: 0.42, dampingFraction: 0.78)) { open = false } }
}
