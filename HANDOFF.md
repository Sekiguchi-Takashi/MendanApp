# 面談文字起こし（MendanApp）HANDOFF  ※ゼロベース再構築

## 経緯

旧構成（録音アプリ OkoshiApp ＋ 評価アプリ MendanApp のフェーズ2/4、
キーワード強調・1/2マーカー・分割）は **廃止**。
単一アプリに再構築した。録音は外部アプリで作った AAC を扱う前提。

踏襲したのは Appathy 規約と Termux+whisper パイプラインの考え方のみ。

## 機能（3つ）

1. **AAC音源を開く・再生する**
   - SAF(OPEN_DOCUMENT, audio/*) で選択
   - MediaPlayer で再生・一時停止・シーク
2. **文字起こし（最長1時間）→ 表示 → 保存**
   - Termux の whisper.cpp(medium) に投げる
   - 結果を EditText に表示（編集可）
   - SAF(CREATE_DOCUMENT) で任意の場所へ .txt 保存
3. **音源の削除**
   - DocumentsContract.deleteDocument（確認ダイアログあり）

## 中核設計：作業フォルダの共有

アプリ(SAF content://)と Termux(実パス)が同じ場所を読み書きするための仕掛け。

- 初回一度だけ「作業フォルダ」を選ぶ（例: Download/okoshi）。永続付与。
- `Prefs.fsPath()` が SAF ツリーの docId "primary:Download/okoshi" を
  `/storage/emulated/0/Download/okoshi` に写像する（primary ボリューム限定）。
- 文字起こし時:
  1. 選んだ AAC を作業フォルダへ `in_<ms>.m4a` としてSAFコピー
  2. `okoshi <実パス>` を RUN_COMMAND(background) で起動
  3. 作業フォルダ(SAF)を10秒間隔で監視し `in_<ms>` を含む .txt を待つ
  4. 出現したら本文を EditText に表示

SDカード等の非 primary は fsPath が null になり弾く。

## Termux 側の前提

- `~/.termux/termux.properties` に `allow-external-apps=true` → `termux-reload-settings`
- `~/bin/okoshi` が実行可能で、whisper.cpp(build) と ggml-medium-q5_0.bin が存在
- **okoshi の OUTDIR を作業フォルダに合わせること**。
  アプリの監視先＝作業フォルダなので、出力もそこに出す必要がある。
  例: 作業フォルダが Download/okoshi なら
      `sed -i 's|^OUTDIR=.*|OUTDIR=~/storage/downloads/okoshi|' ~/bin/okoshi`
- okoshi は入力を ffmpeg で 16kHz/mono/wav に変換してから whisper にかけるので
  AAC/m4a をそのまま渡してよい
- 長時間処理のため okoshi 内で termux-wake-lock を掴む（掴んでいなければ
  `sed -i '/^set -e/a termux-wake-lock 2>/dev/null || true\ntrap "termux-wake-unlock 2>/dev/null || true" EXIT' ~/bin/okoshi`）

## 実測（この端末）

- medium は音声を常に30秒単位で処理。4秒音声でも約39秒（大半がencode固定費）
- encode ≒ 24秒/30秒チャンク。1時間音源 ≒ 120チャンク → **1〜2時間**が目安
- 空きメモリでの medium 起動は確認済み（538MB, OOMなし）
- 遅い場合: OKOSHI_THREADS を上げる / OKOSHI_BEAM=1 / small-q5_1 に落とす

## ビルド規約（Appathy共通・変更不可）

- AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.9 / JDK 17
- Gradle wrapper を置かない（`gradle/actions/setup-gradle@v4`）
- 外部依存ゼロ / XMLレイアウトなし / `android.app.Activity` 直継承
- `debug.keystore` をコミットして署名固定（`git add -f`）
- **`git init` をホームで打たない**（GH013 トークン露出）。`git -C <dir>` を使う

## リポジトリ

- GitHub: `Sekiguchi-Takashi/MendanApp`（既存リポジトリを再構築）
- パッケージ: `com.appathy.mendan`
- 旧 `okoshi` モジュールと旧フェーズ2/4ソースは削除済み

## 次の候補

- 長時間音源の分割投入（30分ごとに区切って部分結果を順次表示）
- 文字起こし中のフォアグラウンド通知（進捗の可視化）
- 作業フォルダ内の入力 .m4a を文字起こし後に自動削除するオプション

## 注意

- 対人支援の面談記録を扱う。全処理が端末内で完結し外部送信ゼロ。
  保持期間・破棄ルールは運用側で先に決める。
- Bonsai 連携は見送り中。再開時は BONSAI_API.md に契約を記載し、
  サーバー側変更は BonsaiApp チャットのみ、新チャットは契約＋HANDOFF.md で開始。
