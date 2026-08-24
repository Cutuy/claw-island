// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ClawIslandMac",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "ClawIslandMac", targets: ["ClawIslandMac"])
    ],
    targets: [
        .executableTarget(name: "ClawIslandMac")
    ],
    swiftLanguageModes: [.v5]
)
