// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guoyingtv_windows/danmaku.dart';
import 'package:guoyingtv_windows/danmaku_layer.dart';
import 'package:guoyingtv_windows/models.dart';

TimedComment at(int ms, String text, [String? id]) =>
    TimedComment(id: id ?? '$ms-$text', text: text, timeMs: ms);

void main() {
  test('同一句在窗口内只聚合成一组并带数量标记', () {
    final groups = repeatGroups([
      at(1000, '好'),
      at(3000, '好'),
      at(5000, '好'),
      at(9000, '别的'),
    ]);
    expect(groups.length, 2);
    expect(groups.first.text, '好');
    expect(groups.first.count, 3);
    expect(groups.first.label, '好 ×3');
    // 只出现一次的不挂「×1」。
    expect(groups.last.label, '别的');
  });

  test('超出窗口的同一句另起一轮', () {
    final groups = repeatGroups([at(0, '哈哈'), at(1000, '哈哈'), at(30000, '哈哈')]);
    expect(groups.length, 2);
    expect(groups.first.count, 2);
    expect(groups.last.count, 1);
    expect(groups.last.firstMs, 30000);
  });

  test('数量标记按播放时间往上走', () {
    final group = repeatGroups([at(0, '冲'), at(2000, '冲'), at(4000, '冲')]).single;
    expect(group.countUpTo(-1), 0);
    expect(group.countUpTo(0), 1);
    expect(group.countUpTo(2000), 2);
    expect(group.countUpTo(9999), 3);
    // 够格进中央的时刻是第二条出现的时候。
    expect(group.eligibleMs, 2000);
  });

  test('中央突出只挑重复过的，且一轮只占一条', () {
    final groups = repeatGroups([
      at(0, '这段太精彩了'),
      at(1000, '这段太精彩了'),
      at(1500, '好看'),
      at(6000, '好看'),
      at(7000, '好看'),
      at(8000, '好看'),
    ]);
    final rounds = planHighlights(groups);
    expect(rounds, isNotEmpty);
    // 每轮都在自己够格的时刻之后开始。
    for (final round in rounds) {
      expect(round.startMs, greaterThanOrEqualTo(round.group.eligibleMs));
      expect(round.endMs, greaterThan(round.startMs));
      expect(round.endMs - round.startMs, greaterThanOrEqualTo(DanmakuHighlight.minDwellMs));
      expect(round.endMs - round.startMs, lessThanOrEqualTo(DanmakuHighlight.maxDwellMs));
    }
    // 不重叠。
    for (var index = 1; index < rounds.length; index++) {
      expect(rounds[index].startMs, greaterThanOrEqualTo(rounds[index - 1].endMs));
    }
    // 同一组只上一次中央。
    final keys = rounds.map((round) => round.group.key).toList();
    expect(keys.toSet().length, keys.length);
  });

  test('补数据不把正在中央展示的那一条换掉', () {
    final first = planHighlights(
      repeatGroups([at(0, '名场面'), at(1000, '名场面')]),
    );
    expect(first, isNotEmpty);
    // 第二窗补进来一条更热的，排序时要接着排原来那条。
    final second = planHighlights(
      repeatGroups([
        at(0, '名场面'),
        at(1000, '名场面'),
        at(1100, '更热'),
        at(1200, '更热'),
        at(1300, '更热'),
        at(1400, '更热'),
      ]),
      first,
    );
    expect(second.first.group.key, first.first.group.key);
    expect(second.first.startMs, first.first.startMs);
  });

  test('字号与透明度吸附到 0.1 并按上下限收住', () {
    expect(DanmakuStyle.scale(1), 1);
    expect(DanmakuStyle.scale(.63), .6);
    expect(DanmakuStyle.scale(.66), .7);
    expect(DanmakuStyle.scale(9), 2);
    expect(DanmakuStyle.scale(.1), .6);
    expect(DanmakuStyle.scale(double.nan), 2);
    expect(DanmakuStyle.alpha(.05), .2);
    expect(DanmakuStyle.alpha(.97), 1);
    expect(DanmakuStyle.percent(.9), '90%');
  });

  test('显示方式与速度档位读坏值时回到默认档', () {
    expect(DanmakuMode.fromStored('single'), DanmakuMode.single);
    expect(DanmakuMode.fromStored('whatever'), DanmakuMode.full);
    expect(DanmakuMode.fromStored(null), DanmakuMode.full);
    expect(DanmakuMode.hidden.showsStyle, isFalse);
    expect(DanmakuMode.full.showsStyle, isTrue);
    expect(DanmakuSpeed.fromStored('fast').factor, 1.5);
    expect(DanmakuSpeed.fromStored(''), DanmakuSpeed.normal);
  });

  test('表情标签翻成字形，认不出来的原样保留', () {
    expect(DanmakuEmoji.displayText('笑死[笑哭]'), '笑死😂');
    expect(DanmakuEmoji.displayText('【赞】'), '👍');
    expect(DanmakuEmoji.displayText('［狗头］'), '🐶');
    expect(DanmakuEmoji.displayText('ok[OK]'), 'ok👌');
    // 正文里本来就有的方括号不会被吃掉。
    expect(DanmakuEmoji.displayText('[前面高能]'), '[前面高能]');
    expect(DanmakuEmoji.displayText('看到[1,2]集'), '看到[1,2]集');
    // 相邻两个标签各翻各的。
    expect(DanmakuEmoji.displayText('[笑哭][赞]'), '😂👍');
  });

  test('表情切成独立片段，文字与表情分开画', () {
    final parts = DanmakuEmoji.parts('哈[笑哭]哈');
    expect(parts.map((part) => part.text).join(), '哈😂哈');
    expect(parts.where((part) => part.emoji).length, 1);
    expect(parts.first.emoji, isFalse);
    // 组合表情（ZWJ）整簇算一个片段。
    final joined = DanmakuEmoji.parts('[笑哭]');
    expect(joined.length, 1);
    expect(joined.single.emoji, isTrue);
  });

  test('标签表与 TV 端保持一致', () {
    // TV 端也是 396 条，改动其中一端时两边一起改。
    expect(DanmakuEmoji.tagCount, 396);
    expect(DanmakuEmoji.glyph('笑哭'), '😂');
    expect(DanmakuEmoji.glyph('ok'), '👌');
  });

  testWidgets('四种显示方式都能画出来', (tester) async {
    final comments = [
      at(0, '这段太精彩了'),
      at(500, '这段太精彩了'),
      at(900, '这段太精彩了'),
      at(1200, '前面的等等我[笑哭]'),
      at(1500, '这段太精彩了'),
    ];
    for (final mode in DanmakuMode.values) {
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: SizedBox(
              width: 800,
              height: 450,
              child: DanmakuLayer(
                comments: comments,
                positionMs: 1600,
                playing: false,
                mode: mode,
                speed: DanmakuSpeed.normal,
                textScale: 1,
                textAlpha: 1,
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull, reason: '${mode.label} 绘制失败');
    }
  });

  testWidgets('隐藏档不占位也不画', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: 800,
            height: 450,
            child: DanmakuLayer(
              comments: [at(0, '好'), at(500, '好')],
              positionMs: 600,
              playing: false,
              mode: DanmakuMode.hidden,
              speed: DanmakuSpeed.normal,
              textScale: 1,
              textAlpha: 1,
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(
      find.descendant(of: find.byType(DanmakuLayer), matching: find.byType(CustomPaint)),
      findsNothing,
    );
  });
}
