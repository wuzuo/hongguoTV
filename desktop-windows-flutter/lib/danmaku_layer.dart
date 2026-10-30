// SPDX-License-Identifier: GPL-3.0-only
// 与 Android TV 客户端 `kotlin-tv/app/.../DanmakuView.kt` 对应的 Windows 渲染层。
// 轨道调度、四种显示方式、等速不追尾、重复聚合与中央突出的规则与 TV 端一致。
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'danmaku.dart';
import 'models.dart';

/// 横穿一屏的基准时长（秒）：速度 = 屏宽 / 这个基准，再乘档位系数。
const double _baseScrollSeconds = 6;

/// 全屏弹幕离顶边的安全边距。
const double _topInset = 28;

/// 上方弹幕：整层只占画面高度的这一份，其余留白。
const double _topModeShare = .25;

/// 上方弹幕 / 只显一行离顶边留的安全边距。
const double _topModeInset = 20;

/// 底部控制栏之上收住，弹幕不压进度条。
const double _bottomInset = 112;

/// 单帧最多画多少条。
const int _maxLinesPerFrame = 160;

/// 同一轨道上前后两条之间至少留这么宽（按字号算）。
const double _laneGapText = .9;

/// 相邻轨道之间至少留这么高（按字号算）：描边与表情都算在这一档里。
const double _laneGapVertical = .35;

/// 一条弹幕最多允许比自己的时间点晚多久出现；再晚就跳过，不做强行叠加。
const int _maxDelayMs = 4000;

/// 中央突出显示的字号倍率与底板留白。
const double _highlightScale = 1.25;
const double _highlightPad = 13;

/// 表情相对字号的大小：比汉字略大一点，跟系统 emoji 的观感一致。
const double _emojiScale = 1.2;

/// 压在播放画面上的弹幕层。
///
/// 布局（哪一条落在哪一轨、什么时候进画）在 [_DanmakuLayerState._layoutFor] 里一次性算好，
/// 每帧只按当前播放位置求 x，不再排序、不再测量。
///
/// **不叠字的做法**：
/// 1. 同一轨道上，后一条要等前一条的尾部离开画面右边才能进画
///    （`freeAt = start + (宽度 + 间距) / 速度`），因此后一条永远追不上前一条；
/// 2. 所有弹幕同速（[DanmakuSpeed] 只改整体速度），宽度不同的两条之间也不会追尾；
/// 3. 轨道高度按字体实际高度 + 描边 + 表情高度算，大字号下相邻两轨也不会碰在一起；
/// 4. 排不下先换轨道，全排不下才往后延迟；延迟超过 [_maxDelayMs] 就跳过这一条。
///
/// 位置由宿主通过 [positionMs] 提供，弹幕层不做时钟：暂停、跳转、倍速都不会和播放器脱节。
/// libmpv 的 `time-pos` 一秒只更新几次，因此两次更新之间用本地时钟补齐，最多补 600ms。
class DanmakuLayer extends StatefulWidget {
  const DanmakuLayer({
    super.key,
    required this.comments,
    required this.positionMs,
    required this.playing,
    required this.mode,
    required this.speed,
    required this.textScale,
    required this.textAlpha,
  });

  final List<TimedComment> comments;
  final int positionMs;
  final bool playing;
  final DanmakuMode mode;
  final DanmakuSpeed speed;
  final double textScale;
  final double textAlpha;

  @override
  State<DanmakuLayer> createState() => _DanmakuLayerState();
}

class _DanmakuLayerState extends State<DanmakuLayer> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final ValueNotifier<int> _clock = ValueNotifier<int>(0);

  int _anchorMs = 0;
  int _anchoredAt = 0;

  _Layout? _layout;
  Object? _layoutKey;

  /// 中央突出显示的次序：数据一窗一窗补上来，排程要把上一份传进去，
  /// 正在中央展示的那一条才不会被新数据挤掉。
  List<DanmakuHighlightRound> _rounds = const [];

  @override
  void initState() {
    super.initState();
    _sync();
    _ticker = createTicker((_) => _clock.value++);
    _ticker.muted = !widget.playing;
    _ticker.start();
  }

  @override
  void didUpdateWidget(DanmakuLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.positionMs != oldWidget.positionMs) _sync();
    _ticker.muted = !widget.playing;
  }

  @override
  void dispose() {
    _ticker.dispose();
    _clock.dispose();
    super.dispose();
  }

  void _sync() {
    _anchorMs = widget.positionMs;
    _anchoredAt = DateTime.now().millisecondsSinceEpoch;
  }

  int get _nowMs {
    if (!widget.playing) return widget.positionMs;
    // 不要比最后一次真实位置多补超过 600ms：卡顿或跳转之后下一次更新就能纠正，
    // 而不是让整层一直漂在画面外。
    final drift = DateTime.now().millisecondsSinceEpoch - _anchoredAt;
    return _anchorMs + (drift > 600 ? 600 : drift);
  }

  @override
  Widget build(BuildContext context) {
    if (widget.mode == DanmakuMode.hidden) return const SizedBox.shrink();
    if (widget.comments.isEmpty) return const SizedBox.shrink();
    return IgnorePointer(
      child: ClipRect(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final size = Size(constraints.maxWidth, constraints.maxHeight);
            if (size.isEmpty) return const SizedBox.shrink();
            final layout = _layoutFor(size);
            return CustomPaint(
              size: size,
              painter: _DanmakuPainter(
                layout: layout,
                positionMs: widget.positionMs,
                nowMs: () => _nowMs,
                repaint: _clock,
              ),
            );
          },
        ),
      ),
    );
  }

  /// 输入（尺寸 / 数据 / 四项设置）没变就复用上一份布局：每帧重新量几百条文本会吃掉整帧预算。
  _Layout _layoutFor(Size size) {
    final key = Object.hash(
      size.width,
      size.height,
      widget.mode,
      widget.speed,
      widget.textScale,
      widget.textAlpha,
      identityHashCode(widget.comments),
    );
    if (_layout != null && _layoutKey == key) return _layout!;
    final layout = _computeLayout(size);
    _layout = layout;
    _layoutKey = key;
    _rounds = layout.rounds;
    return layout;
  }

  _Layout _computeLayout(Size size) {
    final scale = DanmakuStyle.scale(widget.textScale);
    final alpha = DanmakuStyle.alpha(widget.textAlpha);
    // 字号按窗口高度给（1080p 约 34px），再乘用户缩放并夹住上下限：
    // 小窗口与 4K 全屏都不会过大或过小。
    final baseSize = (size.height * .034).clamp(18.0, 34.0);
    final textSize = (baseSize * scale).clamp(10.0, 72.0);
    final highlightSize = (textSize * _highlightScale).clamp(10.0, 72.0);
    final strokeWidth = textSize * .14;
    final probe = _measure('弹幕', textSize, alpha, strokeWidth);
    final textBox = probe.height;
    final emojiBox = textSize * _emojiScale;
    final laneHeight = math.max(textBox, emojiBox) + textSize * _laneGapVertical;
    final laneGap = textSize * _laneGapText;

    final groups = repeatGroups(widget.comments);
    final rounds = planHighlights(groups, _rounds);
    final planned = rounds.map((round) => round.group.key).toSet();

    var laneTop = _topInset;
    var lanes = 0;
    if (widget.mode != DanmakuMode.hidden && laneHeight > 0) {
      final double usable;
      switch (widget.mode) {
        case DanmakuMode.single:
          // 唯一那一行：顶边留出的边距要装得下中央那一块（它比普通弹幕大一档）。
          laneTop = math.max(_topModeInset, highlightSize * .6 + _highlightPad * .5);
          usable = laneHeight;
        case DanmakuMode.top:
          laneTop = _topModeInset;
          usable = math.max(size.height * _topModeShare - laneTop, 0);
        case DanmakuMode.full:
          laneTop = _topInset;
          usable = math.max(math.max(size.height - _bottomInset, laneTop + 1) - laneTop, 0);
        case DanmakuMode.hidden:
          usable = 0;
      }
      lanes = (usable / laneHeight).toInt().clamp(0, 24);
    }

    final lines = <_Line>[];
    if (lanes > 0) {
      final pixelsPerSecond = size.width / _baseScrollSeconds * widget.speed.factor;
      final laneFree = List<double>.filled(lanes, 0);
      // 「只显一行」时唯一的轨道还要给中央那一块让路。
      final blocked = widget.mode == DanmakuMode.single
          ? [for (final round in rounds) [round.startMs / 1000, round.endMs / 1000]]
          : const <List<double>>[];
      for (final group in groups) {
        // 已经排上中央的那几组不再进普通轨道，避免同一句同时出现两次。
        if (planned.contains(group.key)) continue;
        final text = _measure(group.label, textSize, alpha, strokeWidth);
        final want = group.firstMs / 1000;
        final duration = (size.width + text.width) / pixelsPerSecond;
        var lane = 0;
        var start = double.infinity;
        for (var index = 0; index < lanes; index++) {
          var candidate = math.max(laneFree[index], want);
          if (blocked.isNotEmpty) candidate = _avoid(candidate, duration, blocked);
          if (candidate < start) {
            start = candidate;
            lane = index;
          }
        }
        // 排不下：不强行叠加，直接跳过这一条。
        if ((start - want) * 1000 > _maxDelayMs) continue;
        laneFree[lane] = start + (text.width + laneGap) / pixelsPerSecond;
        lines.add(_Line(start, duration, text.width, lane, text));
      }
      lines.sort((a, b) => a.start.compareTo(b.start));
    }

    return _Layout(
      width: size.width,
      height: size.height,
      mode: widget.mode,
      alpha: alpha,
      lines: lines,
      rounds: rounds,
      laneTop: laneTop,
      laneHeight: laneHeight,
      textSize: textSize,
      highlightSize: highlightSize,
    );
  }

  /// 把 [from] 到 `from + duration` 这段区间里所有「中央占着轨道」的时间段让过去。
  double _avoid(double from, double duration, List<List<double>> blocked) {
    var start = from;
    for (final range in blocked) {
      if (start < range[1] && start + duration > range[0]) start = range[1];
    }
    return start;
  }
}

/// 一条已经排好轨道的普通弹幕：起点（秒）、横穿时长（秒）、宽度、轨道、量好的文本。
class _Line {
  const _Line(this.start, this.duration, this.width, this.lane, this.text);

  final double start;
  final double duration;
  final double width;
  final int lane;
  final _Rendered text;
}

class _Layout {
  _Layout({
    required this.width,
    required this.height,
    required this.mode,
    required this.alpha,
    required this.lines,
    required this.rounds,
    required this.laneTop,
    required this.laneHeight,
    required this.textSize,
    required this.highlightSize,
  });

  final double width;
  final double height;
  final DanmakuMode mode;
  final double alpha;
  final List<_Line> lines;
  final List<DanmakuHighlightRound> rounds;
  final double laneTop;
  final double laneHeight;
  final double textSize;
  final double highlightSize;

  /// 中央那一块的文本与数量标记按需量一次就留着：数量只在播放推进时往上走，
  /// 每帧重新排版会把一帧的时间全花在排版上。
  final Map<String, _Rendered> _highlightText = {};
  final Map<String, _Rendered> _counters = {};

  _Rendered highlightText(String text) => _highlightText.putIfAbsent(
    text,
    () => _measure(text, highlightSize, alpha, highlightSize * .16),
  );

  _Rendered counter(String text) => _counters.putIfAbsent(
    text,
    () => _measure(text, highlightSize, alpha, highlightSize * .16),
  );

  /// 中央那一块的纵向中心：跟随显示方式，别贴边。
  double get highlightCenterY => switch (mode) {
    DanmakuMode.single => laneTop + laneHeight / 2,
    DanmakuMode.top => laneTop + (height * _topModeShare - laneTop) / 2,
    _ => height / 2,
  };
}

/// 一段量好的文本：填充与描边各一份，外加宽度。
class _Rendered {
  const _Rendered(this.fill, this.stroke, this.width, this.height);

  final TextPainter fill;
  final TextPainter stroke;
  final double width;
  final double height;
}

TextStyle _fillStyle(double size, double alpha) => TextStyle(
  fontSize: size,
  fontWeight: FontWeight.w600,
  color: Colors.white.withValues(alpha: alpha),
);

TextStyle _emojiStyle(double size) => TextStyle(fontSize: size * _emojiScale);

TextStyle _strokeStyle(double size, double alpha, double strokeWidth) => TextStyle(
  fontSize: size,
  fontWeight: FontWeight.w600,
  // 描边跟着文字一起淡：全黑描边在暗画面上会把字压成一团。
  foreground: Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = strokeWidth
    ..strokeJoin = StrokeJoin.round
    ..color = Colors.black.withValues(alpha: .7 * alpha),
);

TextPainter _painter(String text, TextStyle style, TextStyle emojiStyle, double strokeWidth) {
  final parts = DanmakuEmoji.parts(text);
  final span = parts.length == 1 && !parts.first.emoji
      ? TextSpan(text: text, style: style)
      : TextSpan(
          style: style,
          children: [
            for (final part in parts)
              TextSpan(text: part.text, style: part.emoji ? emojiStyle : null),
          ],
        );
  return TextPainter(text: span, maxLines: 1, textDirection: TextDirection.ltr)..layout();
}

_Rendered _measure(String text, double size, double alpha, double strokeWidth) {
  final fill = _painter(text, _fillStyle(size, alpha), _emojiStyle(size), strokeWidth);
  final stroke = _painter(text, _strokeStyle(size, alpha, strokeWidth), _emojiStyle(size), strokeWidth);
  return _Rendered(fill, stroke, fill.width, fill.height);
}

class _DanmakuPainter extends CustomPainter {
  _DanmakuPainter({
    required this.layout,
    required this.positionMs,
    required this.nowMs,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final _Layout layout;

  /// 宿主给的播放位置。播放中靠 [_clock] 每帧重绘；暂停时位置不变、时钟不走，
  /// 只有跳转会让它变，因此它也必须参与 [shouldRepaint]，否则暂停中拖进度条画面不更新。
  final int positionMs;
  final int Function() nowMs;

  @override
  void paint(Canvas canvas, Size size) {
    if (layout.lines.isEmpty && layout.rounds.isEmpty) return;
    final seconds = nowMs() / 1000.0;
    var drawn = 0;
    for (final line in layout.lines) {
      final elapsed = seconds - line.start;
      // 已按起点排好序：还没到的后面更不会到。
      if (elapsed < 0) break;
      if (elapsed > line.duration) continue;
      if (drawn >= _maxLinesPerFrame) break;
      final x = layout.width - elapsed / line.duration * (layout.width + line.width);
      final top = layout.laneTop + line.lane * layout.laneHeight + (layout.laneHeight - line.text.height) / 2;
      line.text.stroke.paint(canvas, Offset(x, top));
      line.text.fill.paint(canvas, Offset(x, top));
      drawn++;
    }
    _paintHighlight(canvas, nowMs());
  }

  /// 画中央突出显示的那一条：同一时间只有一条，数量标记跟着播放往上走。
  void _paintHighlight(Canvas canvas, int now) {
    final rounds = layout.rounds;
    if (rounds.isEmpty) return;
    // 每轮不重叠且按时间排好：找到最后一个起点不晚于现在的。
    var low = 0;
    var high = rounds.length - 1;
    var index = -1;
    while (low <= high) {
      final mid = (low + high) >> 1;
      if (rounds[mid].startMs <= now) {
        index = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    if (index < 0) return;
    final round = rounds[index];
    if (now >= round.endMs) return;

    final body = layout.highlightText(round.group.text);
    final count = round.group.countUpTo(now);
    final counter = layout.counter(' ×${count < DanmakuHighlight.minCount ? DanmakuHighlight.minCount : count}');
    final total = body.width + counter.width;
    final left = math.max((layout.width - total) / 2, 8.0);
    final centerY = layout.highlightCenterY;
    final padX = _highlightPad;
    final padY = _highlightPad * .5;
    final box = layout.highlightSize * .95;
    // 弹幕密集时后面的字会从这块底板下面穿过，给一层半透明底才读得清。
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTRB(left - padX, centerY - box / 2 - padY, left + total + padX, centerY + box / 2 + padY),
        const Radius.circular(10),
      ),
      Paint()..color = Colors.black.withValues(alpha: .6 * layout.alpha),
    );
    final top = centerY - body.height / 2;
    body.stroke.paint(canvas, Offset(left, top));
    body.fill.paint(canvas, Offset(left, top));
    // 数量标记接在正文后面：与正文同一档字号，先描边后填充。
    final counterTop = centerY - counter.height / 2;
    counter.stroke.paint(canvas, Offset(left + body.width, counterTop));
    counter.fill.paint(canvas, Offset(left + body.width, counterTop));
  }

  @override
  bool shouldRepaint(_DanmakuPainter oldDelegate) =>
      oldDelegate.positionMs != positionMs || !identical(oldDelegate.layout, layout);
}
