# OkoshiApp / 面談録音 HANDOFF

## 位置づけ

面談チェック（MendanApp）の **フェーズ1** を担当する独立アプリ。

```
OkoshiApp        録音 → WAV
   ↓  Termux RUN_COMMAND
~/bin/okoshi     whisper.cpp medium-q5_0 で文字起こし
   ↓  /sdcard/Download/okoshi/*.txt
MendanApp        キーワード強調 → 分割 → 評価（フェーズ2・4）
```

## 設計判断：モデルをアプリに載せない

`medium-q5_0` は約540MB。端末の空きメモリは1.1GB前後・swap枯渇。

Android のアプリプロセスは Termux より **LMK（Low Memory Killer）に落とされやすい**。
バックグラウンドに回った瞬間に殺される。
Termux は常駐用の作りで、`termux-wake-lock` で保護もできる。

よって **推論はすべて Termux 側**。アプリは録音と受け渡しのみ。
whisper.cpp を JNI で組み込む案は、端末を替えるかモデルを
`small` に落とすまで **見送り**。

## 録音仕様

- `AudioRecord` + 自前WAVヘッダで **16kHz / モノラル / PCM16 を直書き**
  - whisper.cpp の要求形式そのもの。Termux 側の ffmpeg 変換が実質パススルーになる
  - 32KB/秒。5分で約9.6MB
- 音源は `VOICE_RECOGNITION`（AGCやノイズ抑制の副作用が少ない）
- **上限5分。到達で自動停止**
- 必ず **フォアグラウンドサービス**。Activity 内録音は画面を離れると切れる
- RMS を毎バッファ計算して保持（レベルメーター用。
  将来の話者分離の粗い手がかりとしても使える）

## 受け渡し

アプリ専用領域 `/Android/data/...` は Android 11 以降 Termux から読めない。
MediaStore 経由で **共有ストレージ** に公開する。

- 保存先: `/sdcard/Download/okoshi_in/mendan_yyyyMMdd_HHmmss.wav`
- Termux から: `~/storage/downloads/okoshi_in/`
- 起動: `com.termux.RUN_COMMAND` で `~/bin/okoshi <絶対パス>` を実行
- `RUN_COMMAND_BACKGROUND=false` にしてTermux画面を開く。
  medium は5〜15分かかるので、進行が見えないと不安になるため

## Termux 側の前提条件

これが揃っていないと起動に失敗する。

1. `~/.termux/termux.properties` に `allow-external-apps=true`
2. `termux-reload-settings` を実行
3. `~/bin/okoshi` に実行権限
4. `termux-setup-storage` 実行済み
5. `~/whisper.cpp/models/ggml-medium-q5_0.bin` が存在

長時間処理のため、実行前に `termux-wake-lock` を推奨。

## ビルド規約（全Appathyプロジェクト共通・変更不可）

- AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.9 / JDK 17
- Gradle wrapper を置かない（`gradle/actions/setup-gradle@v4` で固定）
- **外部依存ゼロ**
- XMLレイアウトなし。プログラマティックUIのみ
- `android.app.Activity` を直接継承
- `debug.keystore` をコミットして署名を固定（`git add -f`）

### 落とし穴

- **`git init` をホームディレクトリで打たない。**
  トークンが Push Protection (GH013) で露出する。必ずプロジェクトへ `cd` してから。

## リポジトリ

- GitHub: `Sekiguchi-Takashi/OkoshiApp`
- パッケージ: `com.appathy.okoshi`

## 次にやること（v0.2）

1. 録音一覧画面（未処理／処理済みの区別、再生、削除）
2. 連番管理（5分区切りで面談1件が複数ファイルになるため、
   セッションIDでまとめる）
3. 出力テキストの取り込み確認と MendanApp への共有
4. 精度が不足する場合の `-bs` / `THREADS` 調整UI

## 注意事項

- 対人支援の面談記録を扱う。**全処理が端末内で完結し外部送信はゼロ**。
  この性質を壊す変更（クラウドSTTの導入など）は、
  同意と記録管理の運用を先に決めてから検討すること。
- トランスクリプトと音声の保持期間・破棄ルールは運用側で先に決める。
- Bonsai 連携は現時点で見送り。再開する場合は
  `BONSAI_API.md` に契約を記載し、サーバー側の変更は BonsaiApp チャットのみが行い、
  新チャットは契約＋HANDOFF.md を貼って開始する。
