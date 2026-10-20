# 點號拍照（PointCam）

Android 上記錄每日工作照片的小工具。

- 按「拍照」進入 App 內的相機，可以連續拍照；右上角顯示目前的「點號:N」，拍完自動加 1，按返回才回主畫面
- 拍照畫面有「修改點號」按鈕；主畫面點一下點號數字也能直接修改
- 照片直接存進手機相簿：`Pictures/工作照片/20261020/3.jpg`（檔名預設就是點號，可加前綴如 `A區_3.jpg`）
- 照片左下角可印「點號:N」、日期時間與工程名稱；點號與時間分開設定
- 重置方式二選一：**隔日自動重置**（每天從點號:1 開始）或 **僅手動重置**
- 「分享這天的全部照片」：一次把整天的照片分享到 LINE、Google 雲端硬碟、Email 等
- 每張照片可「重拍」（保留原點號與檔名）或「刪除」
- 需要 Android 10 以上；第一次拍照會詢問相機權限

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
