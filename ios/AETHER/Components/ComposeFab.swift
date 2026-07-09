import SwiftUI

// Морф-FAB для нового чата на жидком стекле, с squish-нажатием.
struct ComposeFab: View {
    var action: () -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        Button(action: action) {
            Image(systemName: "square.and.pencil")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(palette.onAccent)
                .frame(width: 58, height: 58)
                .background(
                    Circle().fill(
                        LinearGradient(colors: [palette.accent, palette.accent.opacity(0.75)],
                                       startPoint: .top, endPoint: .bottom)
                    )
                )
                .shadow(color: palette.accent.opacity(0.45), radius: 14, y: 6)
        }
        .buttonStyle(.squish)
    }
}
