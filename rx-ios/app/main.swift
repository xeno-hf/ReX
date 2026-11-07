import UIKit
import RexKit

// ReX iOS 進入點：整個畫面與邏輯在 RexKit（Kotlin Multiplatform 共用層，跟 Android 同一份）。
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.rootViewController = IosEntryKt.MainViewController()
        w.makeKeyAndVisible()
        window = w
        return true
    }

    // 從「檔案」或分享面板用 ReX 打開：騎乘紀錄（.loga、XTune 的 zip）或車輛資料 XML
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return IosEntryKt.handleOpenUrl(url: url)
    }
}
