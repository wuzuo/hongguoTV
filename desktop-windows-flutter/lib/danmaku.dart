// SPDX-License-Identifier: GPL-3.0-only
// 与 Android TV 客户端 `kotlin-tv/core/.../Danmaku.kt`、`DanmakuEmoji.kt` 行为保持一致：
// 显示方式 / 速度 / 字号 / 透明度四项设置、重复弹幕聚合、中央突出排程与表情标签映射
// 都是同一套规则，改动其中一端时请同步另一端。
import 'models.dart';

/// 弹幕在画面上的显示方式。存进本机设置，换集与重开应用都保持。
///
/// 顺序就是设置面板里的排列顺序：只显一行 → 上方显示 → 全屏显示 → 隐藏弹幕。
enum DanmakuMode {
  /// 只显一行：整屏只有一条轨道，多密也不会上下叠字。
  single('single', '只显一行'),

  /// 上方显示：只在画面上方区域滚动。
  top('top', '上方显示'),

  /// 全屏显示：整幅画面从上到下都能滚。
  full('full', '全屏显示'),

  /// 隐藏弹幕：整层不画，也不发请求。
  hidden('hidden', '隐藏弹幕');

  const DanmakuMode(this.storedValue, this.label);

  final String storedValue;
  final String label;

  /// 这一档下字号 / 透明度 / 速度还有没有意义。隐藏档整层不画，留着这几项只会让人以为功能坏了。
  bool get showsStyle => this != DanmakuMode.hidden;

  static DanmakuMode fromStored(String? value) {
    for (final mode in DanmakuMode.values) {
      if (mode.storedValue == value) return mode;
    }
    return DanmakuMode.full;
  }
}

/// 弹幕移动速度档位。
///
/// [factor] 是「标准档」的倍数：所有弹幕同速，换档只改变整体横穿画面的时间，
/// 不会让快的追上慢的。
enum DanmakuSpeed {
  slow('slow', '慢速', 0.75),
  normal('normal', '标准', 1),
  fast('fast', '快速', 1.5);

  const DanmakuSpeed(this.storedValue, this.label, this.factor);

  final String storedValue;
  final String label;
  final double factor;

  static DanmakuSpeed fromStored(String? value) {
    for (final speed in DanmakuSpeed.values) {
      if (speed.storedValue == value) return speed;
    }
    return DanmakuSpeed.normal;
  }
}

/// 弹幕文字的外观偏好：字号缩放与不透明度。
///
/// 两项都是连续量（面板上用滑条调），取值一律经过 [scale] / [alpha] 归一：
/// 先吸附到 0.1 的整数倍，再夹进上下限，最后抹掉浮点误差。归一放在这里，
/// 面板上的读数、写盘的值、下次读回来的值才是同一个数。
abstract final class DanmakuStyle {
  static const scaleMin = .6;
  static const scaleMax = 2.0;
  static const scaleStep = .1;
  static const alphaMin = .2;
  static const alphaMax = 1.0;
  static const alphaStep = .1;

  static double scale(double value) => _snap(value, scaleMin, scaleMax, scaleStep);

  static double alpha(double value) => _snap(value, alphaMin, alphaMax, alphaStep);

  /// 面板上的读数：`0.9 → "90%"`。
  static String percent(double value) => '${(value * 100).round()}%';

  static double _snap(double value, double min, double max, double step) {
    // 非法值（NaN / 无穷）落回上限：读坏数据时宁可回到「100%」这一档。
    if (!value.isFinite) return max;
    final snapped = ((value - min) / step).round() * step + min;
    return ((snapped * 100).round() / 100).clamp(min, max);
  }
}

/// 一轮「重复弹幕」聚合：同样的文字在 [danmakuRepeatWindowMs] 之内出现了 [count] 次。
///
/// 同一条内容只显示一次，文案带数量标记（`这个片段太精彩了 ×25`）；只出现过一次的
/// 就是原文，不挂「×1」。
class DanmakuRepeat {
  DanmakuRepeat(this.text, List<int> offsets) : offsets = List.unmodifiable(offsets);

  final String text;
  final List<int> offsets;

  int get count => offsets.length;

  int get firstMs => offsets.first;

  int get lastMs => offsets.last;

  /// 这一组的身份：同一句在后续窗口里还会聚合成新的一轮，key 必须带起始时间。
  String get key => '$text@$firstMs';

  /// 显示文案：重复过的带数量标记。
  String get label => count >= 2 ? '$text ×$count' : text;

  /// 播放到 [nowMs] 为止已经出现过几条：中央那一块的数量标记跟着它往上走。
  int countUpTo(int nowMs) {
    var low = 0;
    var high = offsets.length;
    while (low < high) {
      final mid = (low + high) >> 1;
      if (offsets[mid] <= nowMs) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  /// 够格进中央的时刻：第 [DanmakuHighlight.minCount] 条出现的时候。
  int get eligibleMs => offsets[(DanmakuHighlight.minCount < count ? DanmakuHighlight.minCount : count) - 1];
}

/// 一轮聚合的时间窗口：从这一轮的第一条开始算，窗口内出现的同内容都算进这一轮。
const int danmakuRepeatWindowMs = 20000;

/// 把整集弹幕按内容聚合：同一句文字在窗口内出现多次合成一组，按时间排序。
///
/// 纯函数，与画面无关：弹幕层拿它决定「哪些内容只画一次、数量是多少」。
List<DanmakuRepeat> repeatGroups(
  List<TimedComment> items, {
  int windowMs = danmakuRepeatWindowMs,
}) {
  final sorted = [...items]..sort((a, b) => a.timeMs.compareTo(b.timeMs));
  final byText = <String, List<TimedComment>>{};
  for (final item in sorted) {
    byText.putIfAbsent(item.text.trim(), () => []).add(item);
  }
  final groups = <DanmakuRepeat>[];
  byText.forEach((text, list) {
    if (text.isEmpty) return;
    var bucket = <int>[];
    // Kotlin 用的是 Long.MIN_VALUE；这里用足够小的哨兵，第一条进来时就会被覆盖。
    var windowStart = -1 << 62;
    for (final item in list) {
      // 第一条开一个窗口；窗口内继续累计；超出窗口就是新的一轮。
      if (bucket.isNotEmpty && item.timeMs > windowStart + windowMs) {
        groups.add(DanmakuRepeat(text, bucket));
        bucket = <int>[];
      }
      if (bucket.isEmpty) windowStart = item.timeMs;
      bucket.add(item.timeMs);
    }
    if (bucket.isNotEmpty) groups.add(DanmakuRepeat(text, bucket));
  });
  groups.sort((a, b) {
    final byTime = a.firstMs.compareTo(b.firstMs);
    return byTime != 0 ? byTime : a.text.compareTo(b.text);
  });
  return groups;
}

/// 中央突出显示的参数。
///
/// 同一时间只有一条弹幕获得中央突出显示；它要稳（数量涨了只更新标记、不切走），
/// 并且停留时间有上限，不会长期霸占画面中央。
abstract final class DanmakuHighlight {
  /// 至少重复这么多次才算「重复弹幕」，才有资格进中央。
  static const int minCount = 2;

  /// 一条中央弹幕至少停留多久。
  static const int minDwellMs = 5000;

  /// 停留期间每多出现一条重复，延长多久。
  static const int extendMs = 1200;

  /// 停留上限：再热也不会一直占着中央。
  static const int maxDwellMs = 12000;

  /// 一轮聚合结束后还能被选上中央的尾巴：超过这个时间就不再补展示。
  static const int tailMs = 6000;
}

/// 中央突出显示的一轮：[group] 从 [startMs] 展示到 [endMs]（播放时间轴上的毫秒）。
class DanmakuHighlightRound {
  const DanmakuHighlightRound(this.group, this.startMs, this.endMs);

  final DanmakuRepeat group;
  final int startMs;
  final int endMs;
}

/// 排一遍中央突出显示的次序（纯函数，可单测）。
///
/// - 一轮结束后再选下一条，一轮之内不换；
/// - 选「到那一刻为止累计数量最多」的那一组，数量相同选更早出现的；
/// - 每轮停留 = 基础停留 + 期间每多一条重复延长一点，封顶 [DanmakuHighlight.maxDwellMs]；
/// - [previous] 是上一份数据排出来的次序：数据是一窗一窗补上来的，正在中央展示的那条
///   只要还在候选里就接着排它，补数据不会把中央那一条换掉。
List<DanmakuHighlightRound> planHighlights(
  List<DanmakuRepeat> groups, [
  List<DanmakuHighlightRound> previous = const [],
]) {
  final pending = groups.where((it) => it.count >= DanmakuHighlight.minCount).toList();
  if (pending.isEmpty) return const [];
  final used = <String>{};
  final rounds = <DanmakuHighlightRound>[];
  var cursor = 0;
  while (true) {
    // 已经过去太久的那些（快进之后才拿到的旧聚合）不再补展示。
    for (final group in pending) {
      if (!used.contains(group.key) && cursor > group.lastMs + DanmakuHighlight.tailMs) {
        used.add(group.key);
      }
    }
    String? showing;
    for (final round in previous) {
      if (cursor >= round.startMs && cursor < round.endMs) {
        showing = round.group.key;
        break;
      }
    }
    DanmakuRepeat? best;
    if (showing != null) {
      for (final group in pending) {
        if (group.key == showing &&
            !used.contains(group.key) &&
            group.eligibleMs <= cursor) {
          best = group;
          break;
        }
      }
    }
    if (best == null) {
      // 数量优先、相同数量先到先得。
      for (final group in pending) {
        if (used.contains(group.key) || group.eligibleMs > cursor) continue;
        final counted = group.countUpTo(cursor);
        if (counted < DanmakuHighlight.minCount) continue;
        if (best == null) {
          best = group;
          continue;
        }
        final bestCount = best.countUpTo(cursor);
        if (counted > bestCount || (counted == bestCount && group.firstMs < best.firstMs)) {
          best = group;
        }
      }
    }
    if (best == null) {
      var next = -1;
      for (final group in pending) {
        if (used.contains(group.key)) continue;
        final at = group.eligibleMs > group.firstMs ? group.eligibleMs : group.firstMs;
        if (at > cursor && (next < 0 || at < next)) next = at;
      }
      if (next < 0) break;
      cursor = next;
      continue;
    }
    final end = _roundEnd(best, cursor);
    rounds.add(DanmakuHighlightRound(best, cursor, end));
    used.add(best.key);
    cursor = end;
  }
  return rounds;
}

/// 一轮的结束时刻：基础停留 + 这期间每多一条重复延长一点，封顶在最大停留时长。
int _roundEnd(DanmakuRepeat group, int startMs) {
  var end = startMs + DanmakuHighlight.minDwellMs;
  while (true) {
    final counted = group.countUpTo(end);
    final wanted =
        startMs +
        DanmakuHighlight.minDwellMs +
        (counted - DanmakuHighlight.minCount) * DanmakuHighlight.extendMs;
    final cap = startMs + DanmakuHighlight.maxDwellMs;
    final next = wanted < cap ? wanted : cap;
    if (next <= end) return end;
    end = next;
  }
}

/// 一条弹幕里的一段：文字片段照常画，表情片段按字形放大一档画。
class DanmakuPart {
  const DanmakuPart(this.text, {this.emoji = false});

  final String text;
  final bool emoji;
}

/// 弹幕文本里的表情标签。
///
/// 平台的弹幕里表情不是字形，而是一个方括号包起来的名字：`[笑哭]`、`【赞】`、`［狗头］`
/// （半角方括号、全角方括号与【】三种都出现过）。照原样画出来，屏幕上就是一串 `[笑哭]` 文字，
/// 看不出表情。这里把标签翻成对应的 Unicode 字形，由系统 emoji 字体（Windows 上是
/// Segoe UI Emoji）画出来——TV 端因为 Android 字体不全而配了图片，Windows 直接画字形即可。
///
/// 表按「名字」给：同一张脸在平台上有很多叫法（`笑哭` / `爽` / `大笑` 都指 😆 一类），
/// 认不出来的名字**原样保留**，不会把用户打的字吃掉。
abstract final class DanmakuEmoji {
  /// 标签名 → 表情字形。名字大小写不敏感（`OK` / `okk` 这类混写由 [glyph] 兜底）。
  static const Map<String, String> tags = {
    '偷笑': '🤭',
    '捂嘴笑': '🤭',
    '笑哭': '😂',
    '捂脸': '🤦',
    '微笑': '🙂',
    '大笑': '😆',
    '呲牙': '😁',
    '尬笑': '😅',
    '害羞': '😊',
    '奸笑': '😏',
    '坏笑': '😏',
    '赞': '👍',
    '点赞': '👍',
    '鼓掌': '👏',
    '流泪': '😢',
    '大哭': '😭',
    '泪奔': '😭',
    '发怒': '😠',
    '愤怒': '😡',
    '惊讶': '😮',
    '惊呆': '😲',
    '震惊': '😱',
    '思考': '🤔',
    '灵光一闪': '💡',
    '爱慕': '😍',
    '色': '😍',
    '酷': '😎',
    '酷拽': '😎',
    '调皮': '😛',
    '吐舌': '😛',
    '得意': '😏',
    '闭嘴': '🤐',
    '困': '😪',
    '睡': '😴',
    '睡觉': '😴',
    '晕': '😵',
    '流汗': '😓',
    '冷汗': '😰',
    '亲亲': '😘',
    '飞吻': '😘',
    '拥抱': '🤗',
    '握手': '🤝',
    '祈祷': '🙏',
    '抱拳': '🙏',
    '加油': '💪',
    '耶': '✌️',
    '胜利': '✌️',
    'OK': '👌',
    '爱心': '❤️',
    '比心': '❤️',
    '心碎': '💔',
    '玫瑰': '🌹',
    '礼物': '🎁',
    '庆祝': '🎉',
    '蛋糕': '🎂',
    '啤酒': '🍺',
    '咖啡': '☕️',
    '狗头': '🐶',
    '爽': '😆',
    '怒': '😠',
    '发呆': '😶',
    '抠鼻': '👃',
    '舔屏': '😋',
    '看': '👀',
    '感谢': '🙏',
    '抱抱你': '🤗',
    '尴尬流汗': '😅',
    '戳手手': '👉👈',
    '星星眼': '🤩',
    '杀马特': '😎',
    '黄脸干杯': '🍻',
    '抱紧自己': '🤗',
    '拜拜': '👋',
    '热化了': '🥵',
    '黄脸祈祷': '🙏',
    '懵': '😵',
    '举手': '🙋',
    '加功德': '🙏',
    '摊手': '🤷',
    '无语流汗': '😓',
    '续火花吧': '🔥',
    '点火': '🔥',
    '哭哭': '😢',
    '吐舌小狗': '🐶😛',
    '送花': '💐',
    '爱心手': '❤️',
    '贴贴': '🤗',
    '灵机一动': '💡',
    '打脸': '🤦',
    '机智': '🧠',
    '送心': '💝',
    '666': '🤙',
    '来看我': '👀',
    '一起加油': '💪',
    '哈欠': '😪',
    '衰': '😞',
    '疑问': '❓',
    '泣不成声': '😭',
    '小鼓掌': '👏',
    '大金牙': '😁',
    '石化': '😨',
    '吐血': '😵💔',
    '可怜': '🥺',
    '嘘': '🤫',
    '撇嘴': '😒',
    '憨笑': '😄',
    '抓狂': '😫',
    '钱': '💰',
    '恐惧': '😨',
    '愉快': '😄',
    '快哭了': '🥺',
    '翻白眼': '🙄',
    '互粉': '🤝',
    '我想静静': '😔',
    '委屈': '🥺',
    '鄙视': '😒',
    '再见': '👋',
    '紫薇别走': '🥺👋',
    '听歌': '🎧',
    '求抱抱': '🤗',
    '绝望的凝视': '😑',
    '不失礼貌的微笑': '🙂',
    '不看': '🙈',
    '裂开': '💔',
    '干饭人': '🍚',
    '呆无辜': '😇',
    '白眼': '🙄',
    '猪头': '🐷',
    '冷漠': '😐',
    '暗中观察': '👀',
    '二哈': '🐶',
    '菜狗': '🐶🥬',
    '黑脸': '🌚',
    '展开说说': '💬',
    '蜜蜂狗': '🐶🐝',
    '柴犬': '🐕',
    '摸头': '🖐️',
    '皱眉': '😟',
    '擦汗': '😓',
    '红脸': '😳',
    '做鬼脸': '😜',
    '强': '👍',
    '如花': '🤡',
    '吐': '🤮',
    '惊喜': '🤩',
    '敲打': '🔨',
    '奋斗': '💪',
    '吐彩虹': '🌈',
    '嘿哈': '💪',
    '惊恐': '😱',
    '囧': '😖',
    '难过': '😞',
    '斜眼': '😒',
    '阴险': '😏',
    '悠闲': '😌',
    '咒骂': '🤬',
    '吃瓜群众': '🍉',
    '哭': '😭',
    '笑': '😄',
    '没看够': '🤩',
    '探究': '🔍',
    '吃瓜': '🍉',
    '敬礼': '🫡',
    '求爆更': '🙏',
    '顶帖': '⬆️',
    '什么': '❓',
    '你细品': '🧐',
    '重拳出击': '👊',
    '学会了': '🤓',
    '挑眉': '🤨',
    '馋': '🤤',
    '绿帽子': '🧢',
    '敢怒不敢言': '😤',
    '求求了': '🙏',
    '眼含热泪': '🥺',
    '叹气': '😮‍💨',
    '好开心': '😄',
    '不是吧': '😲',
    '鞠躬': '🙇',
    '躺平': '🛌',
    '九转大肠': '🍲',
    '不你不想': '🙅',
    '一头乱麻': '😵',
    'kisskiss': '😘',
    '你不大行': '👎',
    '噢买尬': '😱',
    '宕机': '😵',
    '苦涩': '😣',
    '逞强落泪': '🥲',
    '求机位-黄脸': '📍',
    '求机位3': '📍',
    '精选': '⭐',
    '强壮': '💪',
    '碰拳': '👊',
    '击掌': '🙌',
    '左上': '↖️',
    '勾引': '😏',
    '拳头': '✊',
    '弱': '👎',
    '右边': '👉',
    '左边': '👈',
    '嘴唇': '💋',
    '凋谢': '🥀',
    '垃圾': '🗑️',
    '撒花': '🎉',
    '加一': '➕',
    '减一': '➖',
    'okk': '👌',
    'V5': '💪',
    '绝': '🤩',
    '给力': '💪',
    '红包': '🧧',
    '屎': '💩',
    '发': '💰',
    '18禁': '🔞',
    '炸弹': '💣',
    '西瓜': '🍉',
    '加鸡腿': '🍗',
    '握爪': '🐾',
    '太阳': '☀️',
    '月亮': '🌙',
    '给跪了': '🙇',
    '蕉绿': '😰',
    '扎心': '💔',
    '胡瓜': '🥒',
    '打call': '📣',
    '栓Q': '😓',
    '雪花': '❄️',
    '圣诞树': '🎄',
    '平安果': '🍎',
    '圣诞帽': '🎅',
    '气球': '🎈',
    '烟花': '🎆',
    '福': '🧧',
    'candy': '🍬',
    '糖葫芦': '🍡',
    '鞭炮': '🧨',
    '元宝': '💰',
    '灯笼': '🏮',
    '锦鲤': '🎏',
    '巧克力': '🍫',
    '戒指': '💍',
    '棒棒糖': '🍭',
    '纸飞机': '✈️',
    '粽子': '🍙',
    '🔍': '1f50d.webp',
    '🫡': '1fae1.webp',
    '⬆️': '2b06-fe0f.webp',
    '🤨': '1f928.webp',
    '🧐': '1f9d0.webp',
    '🤓': '1f913.webp',
    '🤤': '1f924.webp',
    '↖️': '2196-fe0f.webp',
    '☀️': '2600-fe0f.webp',
    '☕️': '2615-fe0f.webp',
    '✈️': '2708-fe0f.webp',
    '✊': '270a.webp',
    '✌️': '270c-fe0f.webp',
    '❄️': '2744-fe0f.webp',
    '❓': '2753.webp',
    '❤️': '2764-fe0f.webp',
    '➕': '2795.webp',
    '➖': '2796.webp',
    '⭐': '2b50.webp',
    '🌈': '1f308.webp',
    '🌙': '1f319.webp',
    '🌚': '1f31a.webp',
    '🌹': '1f339.webp',
    '🍉': '1f349.webp',
    '🍎': '1f34e.webp',
    '🍗': '1f357.webp',
    '🍙': '1f359.webp',
    '🍚': '1f35a.webp',
    '🍡': '1f361.webp',
    '🍫': '1f36b.webp',
    '🍬': '1f36c.webp',
    '🍭': '1f36d.webp',
    '🍲': '1f372.webp',
    '🍺': '1f37a.webp',
    '🍻': '1f37b.webp',
    '🎁': '1f381.webp',
    '🎂': '1f382.webp',
    '🎄': '1f384.webp',
    '🎅': '1f385.webp',
    '🎆': '1f386.webp',
    '🎈': '1f388.webp',
    '🎉': '1f389.webp',
    '🎏': '1f38f.webp',
    '🎧': '1f3a7.webp',
    '🏮': '1f3ee.webp',
    '🐕': '1f415.webp',
    '🐝': '1f41d.webp',
    '🐶': '1f436.webp',
    '🐷': '1f437.webp',
    '🐾': '1f43e.webp',
    '👀': '1f440.webp',
    '👃': '1f443.webp',
    '👈': '1f448.webp',
    '👉': '1f449.webp',
    '👊': '1f44a.webp',
    '👋': '1f44b.webp',
    '👌': '1f44c.webp',
    '👍': '1f44d.webp',
    '👎': '1f44e.webp',
    '👏': '1f44f.webp',
    '💋': '1f48b.webp',
    '💍': '1f48d.webp',
    '💐': '1f490.webp',
    '💔': '1f494.webp',
    '💝': '1f49d.webp',
    '💡': '1f4a1.webp',
    '💣': '1f4a3.webp',
    '💩': '1f4a9.webp',
    '💪': '1f4aa.webp',
    '💬': '1f4ac.webp',
    '💰': '1f4b0.webp',
    '📍': '1f4cd.webp',
    '📣': '1f4e3.webp',
    '🔞': '1f51e.webp',
    '🔥': '1f525.webp',
    '🔨': '1f528.webp',
    '🖐️': '1f590-fe0f.webp',
    '🗑️': '1f5d1-fe0f.webp',
    '😁': '1f601.webp',
    '😂': '1f602.webp',
    '😄': '1f604.webp',
    '😅': '1f605.webp',
    '😆': '1f606.webp',
    '😇': '1f607.webp',
    '😊': '1f60a.webp',
    '😋': '1f60b.webp',
    '😌': '1f60c.webp',
    '😍': '1f60d.webp',
    '😎': '1f60e.webp',
    '😏': '1f60f.webp',
    '😐': '1f610.webp',
    '😑': '1f611.webp',
    '😒': '1f612.webp',
    '😓': '1f613.webp',
    '😔': '1f614.webp',
    '😖': '1f616.webp',
    '😘': '1f618.webp',
    '😛': '1f61b.webp',
    '😜': '1f61c.webp',
    '😞': '1f61e.webp',
    '😟': '1f61f.webp',
    '😠': '1f620.webp',
    '😡': '1f621.webp',
    '😢': '1f622.webp',
    '😣': '1f623.webp',
    '😤': '1f624.webp',
    '😨': '1f628.webp',
    '😪': '1f62a.webp',
    '😫': '1f62b.webp',
    '😭': '1f62d.webp',
    '😮': '1f62e.webp',
    '😮‍💨': '1f62e-200d-1f4a8.webp',
    '😰': '1f630.webp',
    '😱': '1f631.webp',
    '😲': '1f632.webp',
    '😳': '1f633.webp',
    '😴': '1f634.webp',
    '😵': '1f635.webp',
    '😶': '1f636.webp',
    '🙂': '1f642.webp',
    '🙄': '1f644.webp',
    '🙅': '1f645.webp',
    '🙇': '1f647.webp',
    '🙈': '1f648.webp',
    '🙋': '1f64b.webp',
    '🙌': '1f64c.webp',
    '🙏': '1f64f.webp',
    '🛌': '1f6cc.webp',
    '🤐': '1f910.webp',
    '🤔': '1f914.webp',
    '🤗': '1f917.webp',
    '🤙': '1f919.webp',
    '🤝': '1f91d.webp',
    '🤡': '1f921.webp',
    '🤦': '1f926.webp',
    '🤩': '1f929.webp',
    '🤫': '1f92b.webp',
    '🤬': '1f92c.webp',
    '🤭': '1f92d.webp',
    '🤮': '1f92e.webp',
    '🤷': '1f937.webp',
    '🥀': '1f940.webp',
    '🥒': '1f952.webp',
    '🥬': '1f96c.webp',
    '🥲': '1f972.webp',
    '🥵': '1f975.webp',
    '🥺': '1f97a.webp',
    '🧠': '1f9e0.webp',
    '🧢': '1f9e2.webp',
    '🧧': '1f9e7.webp',
    '🧨': '1f9e8.webp',
  };

  /// 一个标签：`[名字]` / `【名字】` / `［名字］`。
  ///
  /// 名字最长 16 个字符，且不含任何括号与换行——这样 `[前面高能]然后[笑哭]` 不会把两段连起来吃掉，
  /// 正文里本来就有的方括号（`[1,2]`、`[2024]`）也大多会落到「认不出的名字」而原样保留。
  static final RegExp _tag = RegExp(
    r'\[([^\[\]【】［］\r\n]{1,16})\]|【([^\[\]【】［］\r\n]{1,16})】|［([^\[\]【】［］\r\n]{1,16})］',
  );

  /// 标签总数（测试与资源核对用）。
  static int get tagCount => tags.length;

  /// 标签名 → 字形；认不出来返回 null（调用方保留原文）。
  static String? glyph(String name) =>
      tags[name] ?? tags[name.toLowerCase()] ?? tags[name.toUpperCase()];

  /// 把文本里的标签换成字形，其余原样保留。
  ///
  /// 纯文本场合用它（日志、测试、没有字形可画的降级路径）；播放画面上的弹幕走 [parts]。
  static String displayText(String text) => text.replaceAllMapped(_tag, (match) {
    for (var index = 1; index <= 3; index++) {
      final name = match.group(index);
      if (name != null && name.isNotEmpty) return glyph(name) ?? match.group(0)!;
    }
    return match.group(0)!;
  });

  /// 切好的片段按弹幕原文缓存。
  ///
  /// 弹幕是**边拉边画**的：每拿到一个 30 秒窗口就整批重排一次轨道，同一批文本要切几十遍。
  /// 缓存只按条数封顶（不按字节），一条弹幕的片段总和就是它自己的长度，512 条撑死几十 KB。
  static final Map<String, List<DanmakuPart>> _cache = {};

  static const int _cacheLines = 512;

  /// 把一条弹幕拆成「文字片段 / 表情片段」。
  ///
  /// 先按标签换字形，再按字形簇逐个切：一个表情可能由多个码位组成（`✌️` 带变体选择符、
  /// `😮‍💨` 用 ZWJ 连、`👉👈` 是两个表情并排），必须整簇一起判断，否则会拆出半个表情。
  static List<DanmakuPart> parts(String text) {
    final cached = _cache[text];
    if (cached != null) return cached;
    final value = _split(text);
    if (_cache.length >= _cacheLines) _cache.remove(_cache.keys.first);
    _cache[text] = value;
    return value;
  }

  static List<DanmakuPart> _split(String text) {
    final out = <DanmakuPart>[];
    final buffer = StringBuffer();
    void flush() {
      if (buffer.isEmpty) return;
      out.add(DanmakuPart(buffer.toString()));
      buffer.clear();
    }

    final plain = displayText(text);
    final runes = plain.runes.toList();
    var index = 0;
    while (index < runes.length) {
      final end = _clusterEnd(runes, index);
      final cluster = String.fromCharCodes(runes, index, end);
      if (_isEmoji(runes[index])) {
        flush();
        out.add(DanmakuPart(cluster, emoji: true));
      } else {
        buffer.write(cluster);
      }
      index = end;
    }
    flush();
    return List.unmodifiable(out);
  }

  /// 从 [start] 起一个字形簇的结束下标：基字符 + 变体选择符 / 肤色 / 键帽，以及 ZWJ 连出去的部分。
  static int _clusterEnd(List<int> runes, int start) {
    var index = start + 1;
    while (index < runes.length) {
      final code = runes[index];
      if (code == 0xFE0E || code == 0xFE0F || code == 0x20E3 || (code >= 0x1F3FB && code <= 0x1F3FF)) {
        index++;
      } else if (code == 0x200D && index + 1 < runes.length) {
        // ZWJ：把连在后面的那个字形也吃进来（`😮‍💨` 这类组合表情）。
        index = _clusterEnd(runes, index + 1);
      } else {
        break;
      }
    }
    return index;
  }

  static bool _isEmoji(int code) =>
      (code >= 0x1F000 && code <= 0x1FAFF) ||
      (code >= 0x2600 && code <= 0x27BF) ||
      (code >= 0x2B00 && code <= 0x2BFF) ||
      code == 0x203C ||
      code == 0x2049 ||
      code == 0x3030 ||
      code == 0x303D ||
      code == 0x00A9 ||
      code == 0x00AE;
}
