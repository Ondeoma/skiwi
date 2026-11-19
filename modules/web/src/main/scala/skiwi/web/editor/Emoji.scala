package skiwi.web.editor

import skiwi.web.Ui

/** 「:」で入力する絵文字（絵文字 名前 別名…。日本語・ひらがな・英語で探せる）. エディタの EmojiSuggest が使う */
object Emoji {

  final case class Item(
    emoji: String,
    name: String,
    keys: String) {

    /** 前方一致用に、名前・別名を語に分けておく */
    val words: Array[String] = keys.split(' ')
  }

  private val Raw =
    """😀 笑顔 えがお smile grin|😃 にっこり happy|😄 笑う わらう laugh|😁 にやり grin|😆 大笑い laughing|😅 汗 苦笑い sweat|🤣 爆笑 rofl|😂 泣き笑い joy|🙂 ほほえみ slight smile|😉 ウインク wink|😊 照れ blush|😇 天使 innocent|🥰 好き love|😍 ハートの目 heart eyes|🤩 キラキラ star struck|😘 キス kiss|😋 おいしい yum|😛 舌 tongue|😜 ふざけ wink tongue|🤪 変な顔 zany|🤔 考える かんがえる thinking|🤨 疑い raised eyebrow|😐 無表情 neutral|😑 真顔 expressionless|😶 無言 silent|🙄 呆れ eyeroll|😏 にやにや smirk|😬 気まずい grimace|😌 安心 relieved|😔 しょんぼり pensive|😪 眠い sleepy|😴 寝る ねる sleeping|😷 マスク mask|🤒 熱 sick|🤕 けが hurt|🤢 気持ち悪い nauseated|🥵 暑い hot|🥶 寒い cold|😵 目が回る dizzy|🤯 驚き mind blown|🥳 お祝い party|😎 サングラス cool|🤓 オタク nerd|🧐 じっくり monocle|😕 困惑 confused|😟 心配 worried|🙁 不満 frown|😮 びっくり open mouth|😲 驚く astonished|😳 赤面 flushed|🥺 お願い pleading|😢 涙 なみだ cry|😭 号泣 sob|😱 恐怖 scream|😖 困る confounded|😣 我慢 persevere|😞 がっかり disappointed|😓 冷や汗 sweat|😩 疲れた weary|😫 くたくた tired|😤 ふんっ triumph|😡 怒り いかり angry|🤬 激怒 cursing|👍 いいね グッド good thumbs up ok|👎 だめ bad thumbs down|👏 拍手 はくしゅ clap|🙌 ばんざい raised hands|🙏 お願い ありがとう pray thanks|🤝 握手 あくしゅ handshake|👋 手を振る やあ wave hello|✋ 手 hand|👌 オーケー ok hand|✌️ ピース peace|🤞 幸運 fingers crossed|👉 右 point right|👈 左 point left|👆 上 point up|👇 下 point down|💪 力こぶ がんばる muscle strong|👀 目 見る みる eyes look|🧠 脳 頭 brain|❤️ ハート 赤 heart love|🧡 オレンジのハート orange heart|💛 黄色のハート yellow heart|💚 緑のハート green heart|💙 青のハート blue heart|💜 紫のハート purple heart|🖤 黒いハート black heart|💔 失恋 broken heart|💯 満点 100 hundred|💢 怒 anger|💥 衝突 boom|💫 めまい dizzy|💦 汗 しずく sweat drops|💨 ダッシュ dash|💬 吹き出し 会話 speech comment|💭 考え thought|💤 眠い zzz|✅ チェック 完了 済み check done ok|☑️ チェックボックス checkbox|✔️ チェック check|❌ バツ だめ cross no ng|❎ バツ印 cross mark|➕ プラス 追加 plus add|➖ マイナス minus|❓ 質問 しつもん question|❗ 注意 びっくり exclamation|‼️ 重要 double exclamation|⚠️ 警告 けいこく 注意 warning caution|⛔ 禁止 きんし no entry|🚫 禁止 prohibited|ℹ️ 情報 じょうほう info information|💡 アイデア ひらめき idea bulb tip|📌 ピン 固定 pin|📍 場所 location pin|📎 クリップ 添付 clip attach|🔗 リンク link|🔒 鍵 ロック 保護 lock|🔓 解錠 unlock|🔑 鍵 かぎ key|🔍 検索 けんさく search|🔔 ベル 通知 bell notification|🔕 通知オフ mute|📣 お知らせ メガホン announce megaphone|📢 アナウンス loudspeaker|📝 メモ memo note|📄 ページ 文書 page document|📃 書類 page curl|📑 資料 bookmark tabs|📚 本 ほん books|📖 読む 本 book open|📒 ノート ledger|📓 ノート notebook|📕 赤い本 closed book|📘 青い本 blue book|📗 緑の本 green book|📙 オレンジの本 orange book|📁 フォルダ folder|📂 開いたフォルダ open folder|🗂️ 分類 dividers|🗃️ ファイルボックス card file|🗄️ キャビネット cabinet|📦 箱 荷物 package box|📊 グラフ 棒グラフ chart bar|📈 上昇 増加 chart up|📉 下降 減少 chart down|📅 カレンダー 日付 calendar date|📆 予定 calendar|🗓️ スケジュール schedule|⏰ 時計 目覚まし alarm|⏳ 砂時計 待ち hourglass|⌛ 時間切れ hourglass done|⏱️ ストップウォッチ stopwatch|🕒 時間 clock|🚀 ロケット 開始 リリース rocket launch release|🎉 お祝い クラッカー party tada|🎊 くす玉 confetti|🎯 目標 ねらい target goal|🏆 優勝 トロフィー trophy|🥇 1位 金メダル gold|⭐ 星 ほし star|🌟 輝く star glow|✨ キラキラ 新しい sparkles new|🔥 炎 人気 急ぎ fire hot|⚡ 電気 はやい zap|☀️ 晴れ sun|☁️ くもり cloud|🌧️ 雨 rain|❄️ 雪 snow|🌈 虹 rainbow|🌸 桜 さくら cherry blossom|🍀 四つ葉 幸運 clover|🌱 芽 新規 seedling|🌍 地球 世界 earth world|🌐 ウェブ インターネット globe web|🏢 会社 オフィス office building|🏠 家 home|🏭 工場 factory|🏫 学校 school|🏥 病院 hospital|🚗 車 car|🚚 配送 トラック truck delivery|✈️ 飛行機 出張 airplane|🚃 電車 train|💻 パソコン PC laptop computer|🖥️ デスクトップ desktop|⌨️ キーボード keyboard|🖱️ マウス mouse|📱 スマホ 携帯 phone mobile|☎️ 電話 telephone|📞 受話器 phone call|📧 メール email mail|✉️ 封筒 envelope|📨 受信 incoming|📤 送信 outbox|📥 受信箱 inbox|🖨️ プリンター printer|💾 保存 フロッピー save floppy|💿 ディスク cd|🔋 電池 battery|🔌 プラグ 電源 plug|⚙️ 設定 歯車 gear settings|🛠️ 工具 修理 tools|🔧 レンチ 修正 wrench fix|🔨 ハンマー hammer|🧰 道具箱 toolbox|🧪 実験 試験 test lab|🔬 顕微鏡 microscope|🧩 パズル 部品 puzzle|🐛 バグ 不具合 bug|🧾 領収書 receipt|💰 お金 money bag|💴 円 yen|💳 カード credit card|🛒 買い物 cart|🎁 プレゼント gift|🎨 デザイン 絵 art design|🖌️ 筆 brush|✏️ 鉛筆 編集 pencil edit|✒️ ペン pen|📏 定規 ruler|📐 三角定規 設計 triangle ruler|✂️ はさみ scissors|🗑️ ゴミ箱 削除 trash delete|♻️ リサイクル recycle|🔄 更新 繰り返し refresh repeat|🔁 リピート repeat|⏩ 早送り fast forward|▶️ 再生 play|⏸️ 一時停止 pause|⏹️ 停止 stop|🆕 新規 NEW new|🆗 OK ok|🆙 UP up|🆒 クール cool|🔴 赤 red circle|🟠 オレンジ orange circle|🟡 黄 yellow circle|🟢 緑 green circle|🔵 青 blue circle|🟣 紫 purple circle|⚫ 黒 black circle|⚪ 白 white circle|🟥 赤い四角 red square|🟩 緑の四角 green square|🟦 青い四角 blue square|⬆️ 上矢印 up arrow|⬇️ 下矢印 down arrow|➡️ 右矢印 right arrow|⬅️ 左矢印 left arrow|↩️ 戻る return|👤 人 ユーザー user person|👥 人々 チーム users team|🧑‍💻 エンジニア 開発者 developer|👨‍💼 ビジネスマン business|🙋 手を挙げる raising hand|🙇 お辞儀 すみません bow sorry|🤷 さあ shrug|🐶 犬 いぬ dog|🐱 猫 ねこ cat|🐼 パンダ panda|🐧 ペンギン penguin|🐢 カメ 遅い turtle slow|☕ コーヒー coffee|🍵 お茶 tea|🍺 ビール beer|🍣 寿司 すし sushi|🍙 おにぎり rice ball|🍰 ケーキ cake|🎂 誕生日 birthday cake"""

  val items: List[Item] = Raw.trim.split('|').toList.map { x =>
    val parts = x.trim.split("\\s+").toList
    Item(parts.head, parts(1), Ui.normalizeForSearch(parts.drop(1).mkString(" ")))
  }

  /** 名前・別名の前方一致を先に、含むものを後に */
  def find(
    q: String,
    limit: Int = 12
  ): List[Item] = {
    val k = Ui.normalizeForSearch(q.trim)
    if (k.isEmpty) items.take(limit)
    else {
      val starts = items.filter(_.words.exists(_.startsWith(k)))
      val first = starts.toSet
      (starts ++ items.filter(e => !first.contains(e) && e.keys.contains(k))).take(limit)
    }
  }

}
