import SwiftUI
import SwiftData

@main
struct AppNameApp: App {
    // Owned here (not `.environment(AppStore())` inline) so a re-evaluated
    // Scene body never swaps in a fresh store and drops navigation state.
    @State private var store = AppStore()

    init() {
        // 100 MB memory / 500 MB disk — mirrors Android's Coil 3 config
        // so image cache behavior is symmetric across platforms.
        URLCache.shared = URLCache(
            memoryCapacity: 100_000_000,
            diskCapacity: 500_000_000
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                // .environment(AuthManager())  // FILL IN: your auth manager
                // Universal Links + custom-scheme dispatch.
                // On iOS 17+, .onOpenURL fires for BOTH Universal Links
                // AND custom schemes — do NOT use .onContinueUserActivity,
                // which silently misses Universal Links.
                //
                // Don't route directly from here. Drop the parsed request
                // into an "intent inbox" the root view consumes once
                // foregrounded — Siri intents, widget URLs, and Top Shelf
                // deep links all arrive through the same inbox, so routing
                // lives in exactly one place.
                .onOpenURL { url in
                    // FILL IN: route by url.scheme, then by path
                    // switch url.scheme {
                    // case "https": store.post(.item(url.lastPathComponent))
                    // case "appname": store.post(.item(url.lastPathComponent))
                    // default: break
                    // }
                }
        }
    }
}

/// One entry point. iOS/iPadOS only in this app.
struct RootView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        platformRoot
            // The ONE place the inbox is consumed: deep links and test doors
            // alike land in the store and are routed here.
            .onChange(of: store.inbox, initial: true) { _, pending in
                if !pending.isEmpty { store.drainInbox() }
            }
            // Test doors (DEBUG builds only; LaunchDoors.current is empty in
            // Release). APP_DOOR_SECONDS bounds the door's activity.
            .task {
                let doors = LaunchDoors.current
                guard !doors.isEmpty, store.openDoorsOnce(doors) else { return }
                if let seconds = doors.doorSeconds {
                    guard (try? await Task.sleep(for: .seconds(seconds))) != nil else { return }
                    store.endDoor()
                }
            }
    }

    @ViewBuilder private var platformRoot: some View {
        ContentView_iOS()
    }
}
