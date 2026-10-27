# SpeakLoop for Android

把「骑车通勤听播客学短语」这件事做成一个**装在手机上的独立 App**，不需要电脑、不需要服务端。

## 和 Web 版的根本区别

| | Web 版 | 这个 App |
|---|---|---|
| 谁来合成语音 | 微软 Edge TTS，**必须跑在服务端** | 手机系统自带 TTS，**完全离线** |
| 要不要一台电脑常开 | 要 | **不要** |
| 要不要联网 | 每次生成音频都要 | 只有「AI 补全例句」需要 |
| 数据存在哪 | 电脑上的 `data/app.db` | 手机 App 私有目录里的 SQLite |

为什么 Web 版非要有服务端：微软 TTS 的握手要 `Origin: chrome-extension://...` 和特定 `User-Agent`，
这两个头浏览器强制管控、页面脚本改不了，所以浏览器直连必然 403。

而安卓自带 `TextToSpeech` 引擎就能读英文，不需要冒充、不需要联网、也不涉及任何灰色地带的接口。
代价是音色取决于手机自带的 TTS 引擎（通常是 Google 的英文音色），不是微软那套神经语音。

## 怎么拿到 APK

不需要装 Android Studio。用 GitHub Actions 在云端编译：

```sh
# 1. 在 GitHub 上新建一个空仓库（不要勾选 README）

# 2. 把本目录推上去
cd speakloop-android
git init
git add .
git commit -m "SpeakLoop Android v1"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

推上去后 GitHub 会自动开始构建（`.github/workflows/build.yml`）：

1. 打开仓库页面 → 顶部 **Actions** 标签
2. 等那条 `Build APK` 变成绿色对勾（第一次大概 3~5 分钟）
3. 点进去 → 页面底部 **Artifacts** → 下载 `speakloop-debug-apk.zip`
4. 解压得到 `app-debug.apk`，传到手机安装

装的时候系统会提示「未知来源」，允许即可。这是自己编译的 debug 包，用 Android 的调试证书签过名，可以正常安装。

> 想重新构建：Actions 页面 → 左侧 `Build APK` → 右侧 `Run workflow`。

## 怎么用

1. 打开 App，首次会自动塞 2 条示例短语
2. 点右上角 **设置**，填上你的 AI 接口（默认已指向 Agnes，只需要填 API Key）
3. 点 **＋ 短语** 添加，勾上「用 AI 补全」会自动生成中文释义、2 句例句、4 句情景对话
4. 点 **▶ 开始复习** —— 会按「短语慢速 → 留白 → 短语正常 → 留白 → 每句例句（留白 4 秒）→ 情景对话 → 结尾」的顺序读，循环不停
5. 息屏也会继续播（前台服务 + 通知栏可以暂停/继续/停止）

留白节奏和 Web 版一致：例句后面留 4 秒，刚好够你跟读一遍。

## v1 有什么

- 短语库：新增 / 删除 / 按复习优先级排序（播得少的在前）
- **语音输入短语**：添加时点「🎤 说一遍」，调系统语音输入界面，不用手打
- AI 补全释义、例句、情景对话
- 复习播放：带留白的顺序朗读、循环、后台播放
- **跟读对比**：点任意一条 → 「🎤 开始跟读」→ 说完立刻逐词对比
  - 红字加删除线 = 漏读（原文有、你没说）
  - 黄字 = 多读（你说了原文没有的）
  - 底部给出命中率百分比

## v1 还没有

- **导出音频文件**（现在是实时朗读，不落地成 mp3；要导出得做 WAV/PCM 拼接）
- 手动编辑已保存的短语（只能删了重加）
- 跟读录音留存（只做识别对比，不保存你的录音文件）

## 关于语音识别的两个前提

1. **语音输入**走系统的 `RecognizerIntent`，不需要授予 App 麦克风权限，但手机上必须装有语音识别服务（一般是 Google）。没有的话会提示「这台手机没有语音识别服务」。
2. **跟读对比**用 App 内的 `SpeechRecognizer`，第一次点会申请录音权限，允许后再点一次。

两者都依赖系统语音服务，大多数国产 ROM 上是可用的；如果识别总是失败，去「设置 → 应用 → 默认应用 → 数字助理/语音输入」确认语音服务没被禁用。


## 目录

```
app/src/main/java/com/speakloop/app/
├── MainActivity.kt      界面，代码直接搭，没有 XML 布局
├── ReviewService.kt     复习播放引擎 + 前台服务 + 通知栏
├── Db.kt                SQLite，例句和对话序列化成 JSON 存在一行里
├── Llm.kt               OpenAI 兼容接口，只依赖 HttpURLConnection
├── Diff.kt              逐词对比（LCS 对齐），纯算法不碰 Android API
└── Phrase.kt            数据类
```

依赖只有 `androidx.appcompat` 和 `androidx.core-ktx`，没有 Room、没有 Retrofit、没有 OkHttp、没有 Compose。
