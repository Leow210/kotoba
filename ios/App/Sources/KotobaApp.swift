import SwiftUI

@main
struct KotobaApp: App {
    var body: some Scene {
        WindowGroup {
            // The controller fills the screen so it can colour the status bar and home indicator strips itself; the page
            // stays inside the safe area (still avoiding the keyboard).
            WebHost().ignoresSafeArea(.container)
                .background(Color(red: 0.98, green: 0.97, blue: 0.95).ignoresSafeArea())
        }
    }
}

struct WebHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> WebViewController { WebViewController() }
    func updateUIViewController(_ controller: WebViewController, context: Context) {}
}
