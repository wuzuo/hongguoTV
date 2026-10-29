// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:window_manager/window_manager.dart';

import '../app_session.dart';
import '../models.dart';
import '../store.dart';
import '../theme.dart';
import '../widgets.dart';

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({
    super.key,
    required this.session,
    required this.detail,
    required this.index,
    this.position = 0,
  });

  final AppSession session;
  final DramaDetail detail;
  final int index;
  final double position;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  late final Player _player;
  late final VideoController _controller;
  late final List<StreamSubscription<dynamic>> _subscriptions;

  int _index = 0;
  String _actualQuality = '';
  bool _loading = true;
  bool _controlsVisible = true;
  bool _episodePanel = false;
  bool _fullscreen = false;
  bool _finished = false;
  String? _error;
  String _loadingMessage = '正在准备播放…';
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  Duration _buffer = Duration.zero;
  bool _playing = false;
  bool _buffering = false;
  int _generation = 0;
  Timer? _hideTimer;
  Timer? _saveTimer;
  DateTime _lastUpload = DateTime.fromMillisecondsSinceEpoch(0);
  bool _scrubbing = false;
  double _scrubValue = 0;

  // Seamless auto-advance: hide the chrome end to end and admit to loading only
  // when handing the next episode to the player takes unusually long.
  bool _seamless = false;
  bool _seamlessSlow = false;
  Timer? _seamlessTimer;
  int _preloadedIndex = -1;

  // Mini player: the same window shrunk to a small always-on-top frame instead of a
  // second window, because the mpv render surface belongs to this Flutter view.
  bool _mini = false;
  Size? _savedSize;
  Offset? _savedPosition;
  bool _savedMaximized = false;

  // Danmaku state.
  List<TimedComment> _danmaku = const [];
  int _danmakuLoadedUntil = 0;
  int _danmakuGeneration = 0;
  bool _danmakuLoading = false;
  bool _danmakuEnabled = true;

  @override
  void initState() {
    super.initState();
    _index = widget.index.clamp(0, widget.detail.episodes.length - 1);
    _player = Player(configuration: const PlayerConfiguration(bufferSize: 32 * 1024 * 1024));
    _controller = VideoController(_player);
    _subscriptions = [
      _player.stream.position.listen((value) {
        if (!mounted) return;
        // A seek (or resume) can jump seconds at a time; discard danmaku windows from
        // the old position so loading resumes around the new one instead of stalling.
        final jump = (value.inMilliseconds - _position.inMilliseconds).abs();
        setState(() {
          _position = value;
          if (jump > 5000) {
            _danmaku = const [];
            _danmakuGeneration += 1;
            _danmakuLoadedUntil = value.inMilliseconds ~/ 30000 * 30000;
          }
        });
        if (_danmakuEnabled && value.inMilliseconds > _danmakuLoadedUntil - 15000) {
          unawaited(_loadDanmaku());
        }
        _maybePreloadNext();
      }),
      _player.stream.duration.listen((value) {
        if (mounted) setState(() => _duration = value);
      }),
      _player.stream.buffer.listen((value) {
        if (mounted) setState(() => _buffer = value);
      }),
      _player.stream.playing.listen((value) {
        if (mounted) setState(() => _playing = value);
      }),
      _player.stream.buffering.listen((value) {
        if (mounted) setState(() => _buffering = value);
      }),
      _player.stream.completed.listen((value) {
        if (value) unawaited(_handleCompleted());
      }),
      _player.stream.error.listen((value) {
        if (mounted && value.isNotEmpty) setState(() => _error = value);
      }),
    ];
    _saveTimer = Timer.periodic(const Duration(seconds: 5), (_) => _saveProgress());
    WidgetsBinding.instance.addPostFrameCallback((_) => _play(_index, position: widget.position));
  }

  @override
  void dispose() {
    _saveProgress();
    _saveTimer?.cancel();
    _hideTimer?.cancel();
    _seamlessTimer?.cancel();
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    // Restore the window chrome before tearing down the render surface.
    if (_fullscreen) unawaited(windowManager.setFullScreen(false));
    if (_mini) {
      // Leaving the player from mini mode must not strand the next screen at 420x260
      // or above every other window.
      unawaited(windowManager.setAlwaysOnTop(false));
      unawaited(windowManager.setMinimumSize(const Size(1080, 700)));
      unawaited(windowManager.setTitle('果映 TV'));
    }
    unawaited(_player.dispose());
    super.dispose();
  }

  String get _episodeId =>
      _index >= 0 && _index < widget.detail.episodes.length ? widget.detail.episodes[_index] : '';

  Future<void> _play(
    int index, {
    double position = 0,
    bool resume = true,
    bool seamless = false,
  }) async {
    if (widget.detail.episodes.isEmpty) return;
    final target = index.clamp(0, widget.detail.episodes.length - 1);
    final generation = ++_generation;
    final resumeMs = (position * 1000).round();
    _saveProgress();
    _hideTimer?.cancel();
    _seamlessTimer?.cancel();
    setState(() {
      _index = target;
      _seamless = seamless;
      _seamlessSlow = false;
      _preloadedIndex = -1;
      _loading = !seamless;
      _controlsVisible = !seamless;
      _finished = false;
      _error = null;
      _loadingMessage = '正在准备第 ${target + 1} 集…';
      _position = Duration.zero;
      _duration = Duration.zero;
      _buffer = Duration.zero;
      _danmaku = const [];
      _danmakuGeneration += 1;
      _danmakuLoadedUntil = resumeMs > 30000 ? resumeMs ~/ 30000 * 30000 : 0;
    });
    if (seamless) {
      _seamlessTimer = Timer(const Duration(milliseconds: 700), () {
        if (mounted && generation == _generation) setState(() => _seamlessSlow = true);
      });
    }
    try {
      final result = await widget.session.api.play(
        widget.detail.episodes[target],
        quality: widget.session.store.quality,
      );
      if (!mounted || generation != _generation) return;
      setState(() => _actualQuality = result.quality);
      await _player.open(
        Media(
          result.url,
          start: position > 0 ? Duration(milliseconds: (position * 1000).round()) : null,
        ),
        play: resume,
      );
      if (!mounted || generation != _generation) return;
      await _player.setRate(widget.session.store.speed);
      if (!mounted || generation != _generation) return;
      _seamlessTimer?.cancel();
      setState(() {
        _loading = false;
        _seamless = false;
        _seamlessSlow = false;
      });
      unawaited(_loadDanmaku());
    } catch (problem) {
      _seamlessTimer?.cancel();
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _seamless = false;
        _seamlessSlow = false;
        _error = problem.toString();
      });
    }
  }

  /// Warms the next episode shortly before the current one ends so the seamless
  /// switch only has to hand an already-parsed stream to the player. Attempted at
  /// most once per episode; a failure falls back to the normal on-demand fetch.
  void _maybePreloadNext() {
    if (!widget.session.store.autoNext) return;
    final next = _index + 1;
    if (next >= widget.detail.episodes.length) return;
    if (_preloadedIndex == next) return;
    if (_duration.inMilliseconds <= 0) return;
    final remaining = _duration.inMilliseconds - _position.inMilliseconds;
    if (remaining < 0 || remaining > 150000) return;
    _preloadedIndex = next;
    unawaited(
      widget.session.api
          .preload(widget.detail.episodes[next], quality: widget.session.store.quality)
          .catchError((_) {}),
    );
  }

  Future<void> _loadDanmaku() async {
    if (!_danmakuEnabled || _danmakuLoading || _episodeId.isEmpty) return;
    final total = _duration.inMilliseconds;
    // Duration arrives shortly after `open`; without it the request cannot describe the
    // episode, so wait for the next position tick instead of sending a made-up length.
    if (total <= 0) return;
    final start = _danmakuLoadedUntil;
    if (_position.inMilliseconds < start - 3000) return;
    if (start >= total) return;
    _danmakuLoading = true;
    final ticket = _danmakuGeneration;
    final episode = _episodeId;
    // The bridge expects the *episode* length here, not the length of the requested
    // window. Passing a fixed 60 s made every page after the first minute invalid, so
    // danmaku silently stopped loading for the rest of the episode.
    final duration = total > start ? total : start + 60000;
    try {
      final page = await widget.session.api.danmaku(
        widget.detail.series.id,
        episode,
        startMs: start,
        durationMs: duration,
      );
      if (!mounted || episode != _episodeId || ticket != _danmakuGeneration) return;
      setState(() {
        // Only the last half minute can still be on screen; keeping every page for a
        // 90-minute episode would grow without bound.
        final cutoff = _position.inMilliseconds - 30000;
        final kept = _danmaku.where((item) => item.timeMs >= cutoff).toList();
        _danmaku = [...kept, ...page.items];
        _danmakuLoadedUntil = page.nextMs > start ? page.nextMs : start + 60000;
      });
    } catch (_) {
      // Danmaku is optional; playback must keep working when the endpoint fails.
    } finally {
      _danmakuLoading = false;
    }
  }

  Future<void> _handleCompleted() async {
    if (!mounted || _finished) return;
    _finished = true;
    _saveProgress(completed: true);
    final next = _index + 1;
    if (widget.session.store.autoNext && next < widget.detail.episodes.length) {
      await _play(next, seamless: true);
    } else if (mounted) {
      setState(() {
        _controlsVisible = true;
        _playing = false;
      });
    }
  }

  void _saveProgress({bool completed = false}) {
    if (_episodeId.isEmpty) return;
    final seconds = _position.inMilliseconds / 1000;
    if (seconds <= 0) return;
    final total = _duration.inMilliseconds / 1000;
    final finished = completed || (total > 0 && seconds >= total - 1);
    final record = WatchRecord(
      series: widget.detail.series,
      episodeId: _episodeId,
      episodeIndex: _index,
      position: seconds,
      duration: total > 0 ? total : 0,
      completed: finished,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    widget.session.store.record(record);
    if (widget.session.loggedIn) {
      final now = DateTime.now();
      if (now.difference(_lastUpload).inSeconds >= 30 || finished) {
        _lastUpload = now;
        unawaited(
          widget.session
              .upload(record, widget.detail.episodes.length)
              .catchError((_) {}),
        );
      }
    }
  }

  void _showControls() {
    if (!_controlsVisible) setState(() => _controlsVisible = true);
    _hideTimer?.cancel();
    if (_playing) {
      _hideTimer = Timer(const Duration(seconds: 4), () {
        if (mounted && _playing && !_episodePanel) setState(() => _controlsVisible = false);
      });
    }
  }

  Future<void> _toggleFullscreen() async {
    if (_mini) return;
    setState(() => _fullscreen = !_fullscreen);
    await windowManager.setFullScreen(_fullscreen);
  }

  /// Shrinks this window into an always-on-top mini player and back. The video is
  /// rendered by this Flutter view, so a second real window would need its own player.
  Future<void> _setMini(bool value) async {
    if (_mini == value) return;
    if (value) {
      if (_fullscreen) await _toggleFullscreen();
      try {
        _savedMaximized = await windowManager.isMaximized();
        if (_savedMaximized) {
          await windowManager.unmaximize();
          // Let Windows settle the restored bounds before reading them.
          await Future<void>.delayed(const Duration(milliseconds: 120));
        }
        _savedSize = await windowManager.getSize();
        _savedPosition = await windowManager.getPosition();
        await windowManager.setMinimumSize(const Size(300, 180));
        await windowManager.setSize(const Size(420, 258));
        await windowManager.setAlignment(Alignment.bottomRight);
        await windowManager.setAlwaysOnTop(true);
        await windowManager.setTitle('${widget.detail.series.title} · 迷你播放');
      } catch (_) {
        // Window management is cosmetic; keep playing if a platform call fails.
      }
      if (!mounted) return;
      setState(() {
        _mini = true;
        _controlsVisible = false;
        _episodePanel = false;
      });
    } else {
      try {
        await windowManager.setAlwaysOnTop(false);
        await windowManager.setTitle('果映 TV');
        await windowManager.setMinimumSize(const Size(1080, 700));
        final size = _savedSize;
        final position = _savedPosition;
        if (size != null) await windowManager.setSize(size);
        if (position != null) await windowManager.setPosition(position);
        if (_savedMaximized) await windowManager.maximize();
      } catch (_) {
        // Keep the app usable even if the window manager rejects a call.
      }
      if (!mounted) return;
      setState(() => _mini = false);
    }
  }

  /// Leaves the player and returns to the detail page. `maybePop` on its own can be a
  /// no-op outside a pushed route, so the pop is explicit and mini mode is restored first.
  Future<void> _exitPlayer() async {
    if (_mini) await _setMini(false);
    // Leave fullscreen while the video surface is still alive; toggling it during
    // `dispose` races the player teardown on Windows.
    if (_fullscreen) await _toggleFullscreen();
    unawaited(_player.pause());
    if (!mounted) return;
    final navigator = Navigator.of(context);
    if (navigator.canPop()) {
      navigator.pop();
    } else {
      await navigator.maybePop();
    }
  }

  Future<void> _handleEscape() async {
    if (_mini) return _setMini(false);
    if (_fullscreen) return _toggleFullscreen();
    await _exitPlayer();
  }

  Future<void> _changeQuality(int value) async {
    if (value == widget.session.store.quality) return;
    widget.session.store.updateQuality(value);
    await _play(_index, position: _position.inMilliseconds / 1000, resume: _playing);
  }

  Future<void> _changeSpeed(double value) async {
    widget.session.store.updateSpeed(value);
    await _player.setRate(value);
  }

  @override
  Widget build(BuildContext context) {
    final episodeCount = widget.detail.episodes.length;
    return Theme(
      data: AppTheme.dark,
      child: Scaffold(
        backgroundColor: Colors.black,
        body: CallbackShortcuts(
          bindings: {
            const SingleActivator(LogicalKeyboardKey.space): () {
              _togglePlay();
              if (!_mini) _showControls();
            },
            const SingleActivator(LogicalKeyboardKey.arrowLeft): () => _seekRelative(-10),
            const SingleActivator(LogicalKeyboardKey.arrowRight): () => _seekRelative(10),
            const SingleActivator(LogicalKeyboardKey.keyF): _toggleFullscreen,
            const SingleActivator(LogicalKeyboardKey.escape): () => unawaited(_handleEscape()),
          },
          child: Focus(
            autofocus: true,
            child: _mini ? _miniPlayer() : _fullPlayer(episodeCount),
          ),
        ),
      ),
    );
  }

  Widget _fullPlayer(int episodeCount) => MouseRegion(
    onHover: (_) => _showControls(),
    child: Stack(
      fit: StackFit.expand,
      children: [
        _videoLayer(),
        if (_loading) _loadingLayer(),
        if (!_loading && _seamless && _seamlessSlow) _seamlessSpinner(),
        if (_error != null) _errorLayer(),
        _controlsLayer(episodeCount),
        if (_episodePanel) _episodePanelLayer(),
      ],
    ),
  );

  Widget _miniPlayer() => Stack(
    fit: StackFit.expand,
    children: [
      GestureDetector(
        behavior: HitTestBehavior.opaque,
        onDoubleTap: () => unawaited(_setMini(false)),
        onPanStart: (_) => unawaited(windowManager.startDragging()),
        child: _videoLayer(danmaku: false),
      ),
      if (_loading) _loadingLayer(),
      if (!_loading && _seamless && _seamlessSlow) _seamlessSpinner(),
      if (_error != null) _errorLayer(),
      _miniOverlay(),
    ],
  );

  Widget _miniOverlay() {
    final scheme = AppTheme.dark.colorScheme;
    final total = _duration.inMilliseconds;
    return Stack(
      children: [
        if (total > 0)
          Align(
            alignment: Alignment.topCenter,
            child: LinearProgressIndicator(
              value: (_position.inMilliseconds / total).clamp(0.0, 1.0),
              minHeight: 2,
              backgroundColor: Colors.white24,
              valueColor: AlwaysStoppedAnimation(scheme.primary),
            ),
          ),
        Align(
          alignment: Alignment.bottomCenter,
          child: Container(
            padding: const EdgeInsets.fromLTRB(4, 14, 2, 2),
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0x00000000), Color(0xD9000000)],
              ),
            ),
            child: Row(
              children: [
                IconButton(
                  tooltip: _playing ? '暂停' : '播放',
                  onPressed: _togglePlay,
                  visualDensity: VisualDensity.compact,
                  iconSize: 22,
                  icon: Icon(_playing ? Icons.pause : Icons.play_arrow, color: Colors.white),
                ),
                Expanded(
                  child: Text(
                    '第 ${_index + 1} 集 · ${widget.detail.series.title}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white70, fontSize: 11),
                  ),
                ),
                IconButton(
                  tooltip: '还原播放器',
                  onPressed: () => unawaited(_setMini(false)),
                  visualDensity: VisualDensity.compact,
                  iconSize: 18,
                  icon: const Icon(Icons.open_in_full, color: Colors.white),
                ),
                IconButton(
                  tooltip: '关闭迷你播放',
                  onPressed: () => unawaited(_exitPlayer()),
                  visualDensity: VisualDensity.compact,
                  iconSize: 18,
                  icon: const Icon(Icons.close, color: Colors.white),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _videoLayer({bool danmaku = true}) => AnimatedOpacity(
    opacity: _error == null ? 1 : 0,
    duration: const Duration(milliseconds: 200),
    child: Video(
      controller: _controller,
      fit: BoxFit.contain,
      controls: (state) => danmaku && _danmakuEnabled
          ? DanmakuLayer(
              comments: _danmaku,
              positionMs: _position.inMilliseconds,
              playing: _playing,
            )
          : const SizedBox.shrink(),
    ),
  );

  Widget _loadingLayer() => Container(
    color: Colors.black.withValues(alpha: 0.72),
    alignment: Alignment.center,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const CircularProgressIndicator(),
        const SizedBox(height: 16),
        Text(_loadingMessage, style: const TextStyle(color: Colors.white70, fontSize: 13)),
      ],
    ),
  );

  /// Deliberately minimal: the frame of the finished episode stays on screen with
  /// no dimming, and only a delayed indicator appears if the hand-off stalls.
  Widget _seamlessSpinner() => IgnorePointer(
    child: Center(
      child: Container(
        width: 46,
        height: 46,
        padding: const EdgeInsets.all(11),
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.42),
          shape: BoxShape.circle,
        ),
        child: const CircularProgressIndicator(strokeWidth: 2.4),
      ),
    ),
  );

  Widget _errorLayer() => Container(
    color: Colors.black.withValues(alpha: 0.9),
    child: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Color(0xFFFF765F), size: 44),
            const SizedBox(height: 12),
            Text(
              _error!,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white70, fontSize: 13),
            ),
            const SizedBox(height: 20),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                FilledButton(onPressed: () => _play(_index), child: const Text('重试')),
                const SizedBox(width: 12),
                TextButton(onPressed: () => unawaited(_exitPlayer()), child: const Text('返回')),
              ],
            ),
          ],
        ),
      ),
    ),
  );

  Widget _controlsLayer(int episodeCount) {
    final colors = AppTheme.dark.colorScheme;
    final visible = !_seamless && (_controlsVisible || !_playing);
    return AnimatedOpacity(
      opacity: visible ? 1 : 0,
      duration: const Duration(milliseconds: 180),
      child: IgnorePointer(
        ignoring: !visible,
        child: Container(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0xB3000000), Color(0x00000000), Color(0xE0000000)],
              stops: [0, 0.35, 1],
            ),
          ),
          child: Column(
            children: [
              _topBar(),
              const Spacer(),
              if (_buffering && !_loading)
                const Padding(
                  padding: EdgeInsets.only(bottom: 12),
                  child: SizedBox(
                    width: 28,
                    height: 28,
                    child: CircularProgressIndicator(strokeWidth: 2.5),
                  ),
                ),
              _centerControls(episodeCount),
              const Spacer(),
              _bottomBar(colors),
            ],
          ),
        ),
      ),
    );
  }

  Widget _topBar() {
    final scheme = AppTheme.dark.colorScheme;
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 8, 16, 0),
        child: Row(
          children: [
            TextButton.icon(
              onPressed: () => unawaited(_exitPlayer()),
              style: TextButton.styleFrom(foregroundColor: Colors.white),
              icon: const Icon(Icons.arrow_back, size: 20),
              label: const Text('返回'),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.detail.series.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600),
                  ),
                  Text(
                    '第 ${_index + 1} 集 · ${_actualQuality.isEmpty ? '${widget.session.store.quality}P' : _actualQuality}',
                    style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: '迷你播放',
              onPressed: () => unawaited(_setMini(true)),
              icon: const Icon(Icons.picture_in_picture_alt, color: Colors.white),
            ),
            IconButton(
              tooltip: _danmakuEnabled ? '关闭弹幕' : '开启弹幕',
              onPressed: () => setState(() => _danmakuEnabled = !_danmakuEnabled),
              icon: Icon(
                _danmakuEnabled ? Icons.subtitles : Icons.subtitles_off,
                color: Colors.white,
              ),
            ),
            IconButton(
              tooltip: '选集',
              onPressed: () => setState(() {
                _episodePanel = true;
                _controlsVisible = false;
              }),
              icon: const Icon(Icons.video_library_outlined, color: Colors.white),
            ),
          ],
        ),
      ),
    );
  }

  Widget _centerControls(int episodeCount) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        IconButton(
          tooltip: '上一集',
          onPressed: _index > 0 ? () => _play(_index - 1) : null,
          iconSize: 34,
          icon: const Icon(Icons.skip_previous, color: Colors.white),
        ),
        const SizedBox(width: 8),
        IconButton(
          tooltip: '后退 10 秒',
          onPressed: () => _seekRelative(-10),
          iconSize: 30,
          icon: const Icon(Icons.replay_10, color: Colors.white),
        ),
        const SizedBox(width: 12),
        _playPauseButton(),
        const SizedBox(width: 12),
        IconButton(
          tooltip: '前进 10 秒',
          onPressed: () => _seekRelative(10),
          iconSize: 30,
          icon: const Icon(Icons.forward_10, color: Colors.white),
        ),
        const SizedBox(width: 8),
        IconButton(
          tooltip: '下一集',
          onPressed: _index + 1 < episodeCount ? () => _play(_index + 1) : null,
          iconSize: 34,
          icon: const Icon(Icons.skip_next, color: Colors.white),
        ),
      ],
    );
  }

  Widget _playPauseButton() => SizedBox(
    width: 64,
    height: 64,
    child: Material(
      color: Colors.white.withValues(alpha: 0.14),
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: _togglePlay,
        child: Icon(
          _playing ? Icons.pause : Icons.play_arrow,
          color: Colors.white,
          size: 40,
        ),
      ),
    ),
  );

  Widget _bottomBar(ColorScheme scheme) {
    final total = _duration.inMilliseconds / 1000;
    final current = _scrubbing ? _scrubValue : _position.inMilliseconds / 1000;
    final buffered = _buffer.inMilliseconds / 1000;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SliderTheme(
              // `context` here belongs to the State, which sits above the player's dark
              // Theme, so the inherited slider colors come from the app's (possibly light)
              // theme. Set them explicitly to keep the progress bar visible on video.
              data: SliderTheme.of(context).copyWith(
                trackHeight: 3,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
                activeTrackColor: scheme.primary,
                inactiveTrackColor: Colors.white24,
                thumbColor: scheme.primary,
                overlayColor: scheme.primary.withValues(alpha: 0.18),
              ),
              child: Stack(
                alignment: Alignment.centerLeft,
                children: [
                  if (total > 0)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: LinearProgressIndicator(
                        value: (buffered / total).clamp(0, 1),
                        minHeight: 3,
                        backgroundColor: Colors.white24,
                        valueColor: const AlwaysStoppedAnimation(Colors.white38),
                      ),
                    ),
                  Slider(
                    value: total > 0 ? current.clamp(0, total) : 0,
                    max: total > 0 ? total : 1,
                    onChangeStart: (value) => setState(() {
                      _scrubbing = true;
                      _scrubValue = value;
                    }),
                    onChanged: (value) => setState(() => _scrubValue = value),
                    onChangeEnd: (value) {
                      setState(() => _scrubbing = false);
                      unawaited(_player.seek(Duration(milliseconds: (value * 1000).round())));
                      _showControls();
                    },
                  ),
                ],
              ),
            ),
            Row(
              children: [
                Text(
                  '${formatClock(current)} / ${formatClock(total)}',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                ),
                const SizedBox(width: 16),
                _speedButton(),
                const SizedBox(width: 8),
                _qualityButton(),
                const Spacer(),
                IconButton(
                  tooltip: '音量',
                  onPressed: () async {
                    final volume = _player.state.volume;
                    await _player.setVolume(volume > 0 ? 0 : 100);
                  },
                  icon: const Icon(Icons.volume_up, color: Colors.white, size: 20),
                ),
                IconButton(
                  tooltip: _fullscreen ? '退出全屏' : '全屏',
                  onPressed: _toggleFullscreen,
                  icon: Icon(
                    _fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
                    color: Colors.white,
                    size: 22,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _speedButton() => PopupMenuButton<double>(
    tooltip: '倍速',
    color: const Color(0xFF1E2026),
    initialValue: widget.session.store.speed,
    onSelected: (value) => unawaited(_changeSpeed(value)),
    itemBuilder: (context) => [
      for (final value in speeds)
        PopupMenuItem(
          value: value,
          child: Text(
            '${value == value.roundToDouble() ? value.toInt() : value}x',
            style: const TextStyle(color: Colors.white),
          ),
        ),
    ],
    child: Text(
      '${widget.session.store.speed == widget.session.store.speed.roundToDouble() ? widget.session.store.speed.toInt() : widget.session.store.speed}x',
      style: const TextStyle(color: Colors.white, fontSize: 13),
    ),
  );

  Widget _qualityButton() => PopupMenuButton<int>(
    tooltip: '清晰度',
    color: const Color(0xFF1E2026),
    initialValue: widget.session.store.quality,
    onSelected: (value) => unawaited(_changeQuality(value)),
    itemBuilder: (context) => [
      for (final value in const [480, 720, 1080])
        PopupMenuItem(
          value: value,
          child: Text('$value P', style: const TextStyle(color: Colors.white)),
        ),
    ],
    child: Text(
      _actualQuality.isEmpty ? '${widget.session.store.quality}P' : _actualQuality,
      style: const TextStyle(color: Colors.white, fontSize: 13),
    ),
  );

  Widget _episodePanelLayer() {
    final scheme = AppTheme.dark.colorScheme;
    return Positioned.fill(
      child: GestureDetector(
        onTap: () => setState(() {
          _episodePanel = false;
          _showControls();
        }),
        child: Container(
          color: Colors.black54,
          child: Align(
            alignment: Alignment.centerRight,
            child: GestureDetector(
              onTap: () {},
              child: Container(
                width: 360,
                height: double.infinity,
                color: const Color(0xFF101114),
                child: SafeArea(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
                        child: Row(
                          children: [
                            const Text(
                              '选集',
                              style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600),
                            ),
                            const Spacer(),
                            IconButton(
                              onPressed: () => setState(() {
                                _episodePanel = false;
                                _showControls();
                              }),
                              icon: Icon(Icons.close, color: scheme.onSurfaceVariant),
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: GridView.builder(
                          padding: const EdgeInsets.all(16),
                          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 4,
                            mainAxisSpacing: 10,
                            crossAxisSpacing: 10,
                            childAspectRatio: 1.5,
                          ),
                          itemCount: widget.detail.episodes.length,
                          itemBuilder: (context, index) {
                            final selected = index == _index;
                            return InkWell(
                              onTap: () {
                                setState(() => _episodePanel = false);
                                unawaited(_play(index));
                              },
                              borderRadius: BorderRadius.circular(10),
                              child: Container(
                                alignment: Alignment.center,
                                decoration: BoxDecoration(
                                  // The panel is always dark, so unselected chips use a fixed
                                  // dark fill instead of the ambient (possibly light) theme —
                                  // otherwise white-on-white makes episode numbers unreadable.
                                  color: selected
                                      ? scheme.primary
                                      : const Color(0xFF26282F),
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: Text(
                                  '${index + 1}',
                                  style: TextStyle(
                                    color: selected ? scheme.onPrimary : Colors.white,
                                    fontSize: 13,
                                    fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _togglePlay() {
    if (_loading) return;
    if (_playing) {
      unawaited(_player.pause());
      _saveProgress();
    } else {
      if (_finished && _position >= _duration - const Duration(seconds: 1)) {
        unawaited(_play(_index));
      } else {
        unawaited(_player.play());
      }
    }
  }

  void _seekRelative(int seconds) {
    if (_duration == Duration.zero) return;
    final target = _position + Duration(seconds: seconds);
    final clamped = target < Duration.zero
        ? Duration.zero
        : target > _duration
        ? _duration
        : target;
    unawaited(_player.seek(clamped));
    setState(() => _position = clamped);
    _showControls();
  }
}

/// Two-lane danmaku layer drawn above the video surface. Entries travel right to left over
/// 8 seconds starting at their timestamp.
///
/// libmpv reports `time-pos` only a few times per second, so a purely
/// position-driven layer would visibly stutter. The ticker extrapolates between player
/// updates using a local clock, and re-anchors whenever a new position arrives.
class DanmakuLayer extends StatefulWidget {
  const DanmakuLayer({
    super.key,
    required this.comments,
    required this.positionMs,
    required this.playing,
  });

  final List<TimedComment> comments;
  final int positionMs;
  final bool playing;

  static const travelMs = 10000;

  @override
  State<DanmakuLayer> createState() => _DanmakuLayerState();
}

class _DanmakuLayerState extends State<DanmakuLayer> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  int _anchorMs = 0;
  int _anchoredAt = 0;

  @override
  void initState() {
    super.initState();
    _sync();
    _ticker = createTicker((_) {
      if (widget.playing) setState(() {});
    });
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
    super.dispose();
  }

  void _sync() {
    _anchorMs = widget.positionMs;
    _anchoredAt = DateTime.now().millisecondsSinceEpoch;
  }

  int get _now {
    if (!widget.playing) return widget.positionMs;
    // Never extrapolate more than 600 ms past the last real update: a stalled or seeked
    // player then resyncs on the next update instead of drifting.
    final drift = DateTime.now().millisecondsSinceEpoch - _anchoredAt;
    return _anchorMs + (drift > 600 ? 600 : drift);
  }

  @override
  Widget build(BuildContext context) {
    if (widget.comments.isEmpty) return const SizedBox.shrink();
    final positionMs = _now;
    return IgnorePointer(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final width = constraints.maxWidth;
          const lanes = 3;
          final laneHeight = 28.0;
          final visible = <Widget>[];
          for (final item in widget.comments) {
            final elapsed = positionMs - item.timeMs;
            if (elapsed < 0 || elapsed > DanmakuLayer.travelMs) continue;
            final progress = elapsed / DanmakuLayer.travelMs;
            final x = width - (width + 320) * progress;
            // A time bucket keeps a comment in the same lane even when older pages are
            // pruned from the front of the list (an index-based lane would jump).
            final lane = (item.timeMs ~/ 2000) % lanes;
            visible.add(
              Positioned(
                left: x,
                top: 12 + lane * laneHeight,
                child: Text(
                  item.text,
                  maxLines: 1,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    shadows: [Shadow(blurRadius: 4, color: Colors.black87)],
                  ),
                ),
              ),
            );
          }
          return ClipRect(child: Stack(children: visible));
        },
      ),
    );
  }
}
