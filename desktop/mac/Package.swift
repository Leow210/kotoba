// swift-tools-version:5.9
import PackageDescription

// Kotoba for Mac: a window around the Kotoba interface (served by the Java core) plus an mpv video player.
let package = Package(
    name: "Kotoba",
    platforms: [.macOS(.v13)],
    targets: [
        .systemLibrary(name: "CMpv", path: "Sources/CMpv"),
        .executableTarget(
            name: "Kotoba",
            dependencies: ["CMpv"],
            path: "Sources/Kotoba",
            swiftSettings: [.unsafeFlags(["-I/opt/homebrew/include"])],
            linkerSettings: [.unsafeFlags(["-L/opt/homebrew/lib", "-Xlinker", "-rpath", "-Xlinker", "/opt/homebrew/lib"]), .linkedLibrary("mpv")]
        ),
    ]
)
