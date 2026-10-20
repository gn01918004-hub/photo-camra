# 點號拍照（PointCam）

Android 上記錄每日工作照片的小工具。

- 打開 App 按「拍照」就直接開相機，照片依序記為點 1、點 2、點 3…
- 照片直接存進手機相簿：`Pictures/工作照片/20261020/20261020_點03.jpg`
- 照片左下角可印上「點 N」、日期時間與工程名稱（設定裡可關閉）
- 重置方式二選一：**隔日自動重置**（每天從點 1 開始）或 **僅手動重置**
- 「分享這天的全部照片」：一次把整天的照片分享到 LINE、Google 雲端硬碟、Email 等
- 每張照片可「重拍」（保留原點號與檔名）或「刪除」
- 需要 Android 10 以上；不需要任何權限

## 編譯成 APK

### 方法一：Android Studio

1. 安裝 [Android Studio](https://developer.android.com/studio)。
2. 「Open」選擇這個 `PointCam` 資料夾，等它同步完成（第一次會自動下載需要的元件）。
3. 選單 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
4. 產生的檔案在 `app/build/outputs/apk/debug/app-debug.apk`，傳到手機安裝即可
   （手機需允許「安裝未知來源的應用程式」）。

也可以用 USB 連接手機（開啟開發人員選項的 USB 偵錯），直接按 ▶ Run 安裝。

### 方法二：GitHub 自動編譯（電腦不用裝任何東西）

1. 在 GitHub 建一個新的 repository，把這個資料夾的所有檔案上傳
   （包含隱藏的 `.github` 資料夾）。
2. 上傳後 GitHub 會自動開始編譯，到 repository 的 **Actions** 分頁查看。
3. 完成後（約 3–5 分鐘）點進那次執行，在最下方 **Artifacts** 下載 `PointCam-apk`，
   解壓縮後的 `.apk` 傳到手機安裝。
