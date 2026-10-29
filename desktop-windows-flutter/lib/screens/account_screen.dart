// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_session.dart';
import '../models.dart';
import '../store.dart';
import '../widgets.dart';
import 'detail_screen.dart';

const _cloudTypes = ['账户历史', '账户收藏', '已上线预约', '待上线预约'];

/// Red-fruit account: QR or SMS sign-in plus the four read-only cloud lists. The session
/// itself is encrypted with DPAPI (see [SecretStore]) so it only works for the current
/// Windows user.
class AccountScreen extends StatefulWidget {
  const AccountScreen({super.key, required this.session});

  final AppSession session;

  @override
  State<AccountScreen> createState() => _AccountScreenState();
}

class _AccountScreenState extends State<AccountScreen> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: _cloudTypes.length, vsync: this);
  final Map<String, _CloudState> _cloud = {};

  @override
  void initState() {
    super.initState();
    _tabs.addListener(_onTabChanged);
    if (widget.session.loggedIn) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _onTabChanged());
    }
  }

  @override
  void dispose() {
    _tabs.removeListener(_onTabChanged);
    _tabs.dispose();
    super.dispose();
  }

  void _onTabChanged() {
    if (_tabs.indexIsChanging) return;
    final type = _cloudTypes[_tabs.index];
    final state = _cloud[type];
    if (state == null || (!state.loaded && !state.loading)) {
      unawaited(_loadCloud(type));
    }
  }

  Future<void> _loadCloud(String type) async {
    if (!widget.session.loggedIn) return;
    final state = _cloud.putIfAbsent(type, _CloudState.new);
    if (state.loading) return;
    setState(() {
      state.loading = true;
      state.error = null;
    });
    try {
      final items = await widget.session.api.cloud(
        type,
        online: type != '待上线预约',
      );
      if (!mounted) return;
      setState(() {
        state.items = items;
        state.loaded = true;
        state.loading = false;
      });
    } catch (problem) {
      if (!mounted) return;
      setState(() {
        state.loading = false;
        state.error = problem.toString();
      });
    }
  }

  Future<void> _logout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('退出红果账号'),
        content: const Text('退出后需要重新登录，本机收藏和观看记录会保留。'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('退出')),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.session.api.logout();
    } catch (_) {
      // The local session is cleared regardless of the bridge response.
    }
    SecretStore.delete();
    widget.session.setAccount(null);
    _cloud.clear();
    if (mounted) setState(() {});
  }

  void _signedIn(AccountInfo info) {
    final credentials = info.credentials;
    if (credentials != null) {
      try {
        SecretStore.save(jsonEncode(credentials));
      } catch (_) {
        // A failed save only costs the auto-login on the next start.
      }
    }
    widget.session.setAccount(info);
    _cloud.clear();
    if (mounted) {
      setState(() {});
      _onTabChanged();
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.session,
      builder: (context, _) {
        final account = widget.session.account;
        if (!widget.session.loggedIn) {
          return _LoginPanel(session: widget.session, onSignedIn: _signedIn);
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
              child: Row(
                children: [
                  CircleAvatar(
                    radius: 20,
                    backgroundColor: Theme.of(context).colorScheme.primaryContainer,
                    child: Icon(
                      Icons.person,
                      color: Theme.of(context).colorScheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          account?.nickname ?? '红果用户',
                          style: Theme.of(
                            context,
                          ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                        ),
                        Text(
                          '账户片单只读 · 进度会同步到红果账号',
                          style: TextStyle(
                            fontSize: 12,
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                  OutlinedButton(onPressed: _logout, child: const Text('退出登录')),
                ],
              ),
            ),
            TabBar(
              controller: _tabs,
              isScrollable: true,
              tabAlignment: TabAlignment.start,
              tabs: [for (final type in _cloudTypes) Tab(text: type)],
            ),
            Expanded(
              child: TabBarView(
                controller: _tabs,
                children: [for (final type in _cloudTypes) _cloudView(type)],
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _cloudView(String type) {
    final state = _cloud[type];
    if (state == null || (!state.loaded && !state.loading)) {
      return Center(
        child: FilledButton(onPressed: () => _loadCloud(type), child: const Text('读取账户片单')),
      );
    }
    if (state.loading && !state.loaded) {
      return const Center(child: CircularProgressIndicator());
    }
    if (state.error != null && state.items.isEmpty) {
      return StatusPanel(
        icon: Icons.cloud_off,
        title: '$type读取失败',
        message: state.error!,
        actionLabel: '重新读取',
        onAction: () => _loadCloud(type),
      );
    }
    if (state.items.isEmpty) {
      return StatusPanel(
        icon: Icons.inbox_outlined,
        title: '账号暂无此类记录',
        message: '可以在其他设备上操作后刷新。',
        actionLabel: '刷新',
        onAction: () => _loadCloud(type),
      );
    }
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
          child: Row(
            children: [
              Text(
                '${state.items.length} 部作品',
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              const Spacer(),
              RefreshAction(loading: state.loading, onPressed: () => _loadCloud(type)),
            ],
          ),
        ),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) => GridView.builder(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
              gridDelegate: dramaGridDelegate(constraints.maxWidth),
              itemCount: state.items.length,
              itemBuilder: (context, index) {
                final entry = state.items[index];
                return DramaTile(
                  series: entry.series,
                  subtitle: _entrySubtitle(type, entry),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => DetailScreen(session: widget.session, series: entry.series),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ],
    );
  }

  static String _entrySubtitle(String type, CloudEntry entry) {
    if (type == '账户历史') {
      final seconds = entry.position.toInt();
      final clock =
          '${(seconds ~/ 60).toString().padLeft(2, '0')}:${(seconds % 60).toString().padLeft(2, '0')}';
      final episode = (entry.episodeIndex ?? 0) + 1;
      return '第 $episode 集 · $clock';
    }
    if (type == '账户收藏') return '账号收藏';
    if (entry.publishText?.isNotEmpty == true) return entry.publishText!;
    return type;
  }
}

class _CloudState {
  List<CloudEntry> items = const [];
  bool loading = false;
  bool loaded = false;
  String? error;
}

class _LoginPanel extends StatefulWidget {
  const _LoginPanel({required this.session, required this.onSignedIn});

  final AppSession session;
  final ValueChanged<AccountInfo> onSignedIn;

  @override
  State<_LoginPanel> createState() => _LoginPanelState();
}

enum _LoginMode { qr, sms }

class _LoginPanelState extends State<_LoginPanel> {
  _LoginMode _mode = _LoginMode.qr;

  // QR sign-in
  Timer? _poll;
  String _loginId = '';
  String _image = '';
  DateTime? _deadline;
  bool _busy = false;
  bool _checking = false;
  String? _message;

  // Phone + verification code sign-in
  final _mobile = TextEditingController();
  final _code = TextEditingController();
  Timer? _cooldownTimer;
  int _cooldown = 0;
  bool _sending = false;
  bool _verifying = false;
  bool _codeSent = false;
  String? _smsMessage;

  @override
  void dispose() {
    _poll?.cancel();
    _cooldownTimer?.cancel();
    _mobile.dispose();
    _code.dispose();
    if (_loginId.isNotEmpty) unawaited(_cancel());
    super.dispose();
  }

  void _switchMode(_LoginMode mode) {
    if (mode == _mode) return;
    if (_mode == _LoginMode.qr && _loginId.isNotEmpty) unawaited(_cancel());
    setState(() {
      _mode = mode;
      _message = null;
      _smsMessage = null;
    });
  }

  Future<void> _sendCode() async {
    if (_sending || _cooldown > 0) return;
    final raw = _mobile.text.trim();
    if (raw.isEmpty) {
      setState(() => _smsMessage = '请输入手机号。');
      return;
    }
    setState(() {
      _sending = true;
      _smsMessage = null;
    });
    try {
      final sent = await widget.session.api.smsSend(raw);
      if (!mounted) return;
      setState(() {
        _sending = false;
        _codeSent = true;
        _smsMessage = '验证码已发送到 ${sent.masked}。';
      });
      _startCooldown(sent.retrySeconds);
    } catch (problem) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _smsMessage = problem.toString();
      });
    }
  }

  void _startCooldown(int seconds) {
    _cooldownTimer?.cancel();
    setState(() => _cooldown = seconds);
    _cooldownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() => _cooldown = _cooldown > 0 ? _cooldown - 1 : 0);
      if (_cooldown <= 0) timer.cancel();
    });
  }

  Future<void> _verify() async {
    if (_verifying) return;
    final code = _code.text.trim();
    if (!RegExp(r'^[0-9]{4,8}$').hasMatch(code)) {
      setState(() => _smsMessage = '请输入 4—8 位数字验证码。');
      return;
    }
    setState(() {
      _verifying = true;
      _smsMessage = null;
    });
    try {
      final info = await widget.session.api.smsVerify(code);
      if (!mounted) return;
      setState(() => _verifying = false);
      widget.onSignedIn(info);
    } catch (problem) {
      // A rejected code keeps the bridge session alive, so the user can retype it.
      if (!mounted) return;
      setState(() {
        _verifying = false;
        _smsMessage = problem.toString();
      });
    }
  }

  Future<void> _begin() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
      _image = '';
    });
    try {
      final qr = await widget.session.api.accountQr();
      final image = qr['image'] as String? ?? '';
      final loginId = qr['loginID'] as String? ?? '';
      if (image.isEmpty || loginId.isEmpty) throw StateError('二维码数据不完整');
      if (!mounted) return;
      final expiresIn = (qr['expiresIn'] as num?)?.toInt() ?? 180;
      setState(() {
        _image = image;
        _loginId = loginId;
        _deadline = DateTime.now().add(Duration(seconds: expiresIn));
        _busy = false;
        _message = '请使用红果 App 扫码登录。';
      });
      _poll?.cancel();
      _poll = Timer.periodic(const Duration(seconds: 2), (_) => _check());
    } catch (problem) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _message = problem.toString();
      });
    }
  }

  Future<void> _check() async {
    if (_loginId.isEmpty || _checking) return;
    _checking = true;
    final deadline = _deadline;
    if (deadline != null && DateTime.now().isAfter(deadline)) {
      _poll?.cancel();
      _checking = false;
      if (mounted) {
        setState(() {
          _image = '';
          _loginId = '';
          _message = '二维码已过期，请重新获取。';
        });
      }
      return;
    }
    try {
      final info = await widget.session.api.poll(_loginId);
      if (!mounted) return;
      if (info.loggedIn) {
        _poll?.cancel();
        setState(() {
          _image = '';
          _loginId = '';
        });
        widget.onSignedIn(info);
      }
    } catch (problem) {
      // A transient poll failure should not drop the QR code; retry next tick.
      if (mounted) setState(() => _message = problem.toString());
    } finally {
      _checking = false;
    }
  }

  Future<void> _cancel() async {
    _poll?.cancel();
    final loginId = _loginId;
    _loginId = '';
    if (loginId.isEmpty) return;
    try {
      await widget.session.api.cancelLogin(loginId);
    } catch (_) {
      // Nothing else to do; the server also expires stale logins.
    }
    if (mounted) {
      setState(() {
        _image = '';
        _message = '已取消扫码。';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(32),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerLow,
              borderRadius: BorderRadius.circular(18),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Center(child: Icon(Icons.account_circle_outlined, size: 44, color: scheme.primary)),
                const SizedBox(height: 12),
                Text(
                  '登录红果账号',
                  textAlign: TextAlign.center,
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 8),
                Text(
                  '登录后可查看账户历史、收藏与预约，并在正式观看时同步进度。\n'
                  '登录态使用 Windows DPAPI 绑定当前用户加密保存。',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 13, height: 1.6, color: scheme.onSurfaceVariant),
                ),
                const SizedBox(height: 18),
                SegmentedButton<_LoginMode>(
                  segments: const [
                    ButtonSegment(
                      value: _LoginMode.qr,
                      icon: Icon(Icons.qr_code_2, size: 18),
                      label: Text('扫码登录'),
                    ),
                    ButtonSegment(
                      value: _LoginMode.sms,
                      icon: Icon(Icons.sms_outlined, size: 18),
                      label: Text('短信登录'),
                    ),
                  ],
                  selected: {_mode},
                  showSelectedIcon: false,
                  onSelectionChanged: (value) => _switchMode(value.first),
                ),
                const SizedBox(height: 18),
                if (_mode == _LoginMode.qr) ..._qrContent() else ..._smsContent(scheme),
                const SizedBox(height: 14),
                Text(
                  '账号密码不会经过本客户端；本机数据目录：${AppDirs.data.path}',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _qrContent() {
    final scheme = Theme.of(context).colorScheme;
    return [
      if (_image.isNotEmpty) ...[
        Center(
          child: Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Image.memory(
              base64Decode(_image),
              width: 220,
              height: 220,
              fit: BoxFit.contain,
              gaplessPlayback: true,
            ),
          ),
        ),
        const SizedBox(height: 12),
        Center(child: OutlinedButton(onPressed: _cancel, child: const Text('取消扫码'))),
      ] else
        FilledButton.icon(
          onPressed: _busy ? null : _begin,
          icon: _busy
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.qr_code, size: 18),
          label: Text(_busy ? '正在获取二维码…' : '获取登录二维码'),
        ),
      if (_message != null) ...[
        const SizedBox(height: 14),
        Text(
          _message!,
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
        ),
      ],
    ];
  }

  List<Widget> _smsContent(ColorScheme scheme) {
    return [
      TextField(
        controller: _mobile,
        keyboardType: TextInputType.phone,
        decoration: const InputDecoration(
          labelText: '手机号',
          hintText: '13800138000 或 +886 912345678',
          border: OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 12),
      Row(
        children: [
          Expanded(
            child: TextField(
              controller: _code,
              keyboardType: TextInputType.number,
              inputFormatters: [
                FilteringTextInputFormatter.digitsOnly,
                LengthLimitingTextInputFormatter(8),
              ],
              onSubmitted: (_) => unawaited(_verify()),
              decoration: const InputDecoration(
                labelText: '验证码',
                hintText: '4—8 位数字',
                border: OutlineInputBorder(),
              ),
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 150,
            child: OutlinedButton(
              onPressed: _sending || _cooldown > 0 ? null : _sendCode,
              child: Text(
                _sending
                    ? '发送中…'
                    : _cooldown > 0
                    ? '$_cooldown 秒后获取'
                    : (_codeSent ? '重新获取验证码' : '获取验证码'),
              ),
            ),
          ),
        ],
      ),
      const SizedBox(height: 14),
      FilledButton.icon(
        onPressed: _verifying ? null : _verify,
        icon: _verifying
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.login, size: 18),
        label: Text(_verifying ? '正在登录…' : '登录'),
      ),
      if (_smsMessage != null) ...[
        const SizedBox(height: 12),
        Text(
          _smsMessage!,
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
        ),
      ],
      const SizedBox(height: 10),
      Text(
        '短信由红果平台发送，可能产生费用；验证码错误时可以重新输入，但不要反复获取。'
        '若短信登录失败，可以改用扫码登录。',
        textAlign: TextAlign.center,
        style: TextStyle(fontSize: 11, height: 1.5, color: scheme.onSurfaceVariant),
      ),
    ];
  }
}
