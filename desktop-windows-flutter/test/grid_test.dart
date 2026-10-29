// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guoyingtv_windows/widgets.dart';

/// The desktop grid is a deliberate size choice: 200 px cards were reported as too large.
/// These assertions keep the cards compact and stop the row height from shrinking below the
/// 2:3 cover plus a two-line title, which would overflow the tile.
void main() {
  test('catalog cards stay compact and leave room for the caption', () {
    for (final width in [1024.0, 1280.0, 1600.0, 1920.0, 2560.0]) {
      final delegate = dramaGridDelegate(width);
      expect(delegate, isA<SliverGridDelegateWithFixedCrossAxisCount>());
      final grid = delegate as SliverGridDelegateWithFixedCrossAxisCount;
      final columns = grid.crossAxisCount;
      final spacing = grid.crossAxisSpacing;
      final card = (width - 40) / columns - spacing * (columns - 1) / columns;
      expect(
        card,
        inInclusiveRange(140, 185),
        reason: '窗口 ${width.toInt()} 宽时列数 $columns、卡片 $card px',
      );
      final extent = grid.mainAxisExtent!;
      expect(extent, greaterThanOrEqualTo(card * 1.5 + 58), reason: '卡片 $card px 的封面加文字');
      expect(extent, lessThanOrEqualTo(card * 1.5 + 80), reason: '行距不应留下大片空白');
    }
  });

  test('narrow windows fall back to three columns', () {
    final grid = dramaGridDelegate(480) as SliverGridDelegateWithFixedCrossAxisCount;
    expect(grid.crossAxisCount, 3);
  });
}
