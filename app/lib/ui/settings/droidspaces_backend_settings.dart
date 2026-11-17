import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import 'settings_widgets.dart';

/// The root-managed Droidspaces backend: daemon + boot module + SELinux rules.
/// Shown on the engine page so a normal install is not hidden under developer
/// options; the same actions are also in the developer settings.
class DroidspacesBackendSettings extends ConsumerStatefulWidget {
  const DroidspacesBackendSettings({super.key});

  @override
  ConsumerState<DroidspacesBackendSettings> createState() => _DroidspacesBackendSettingsState();
}

class _DroidspacesBackendSettingsState extends ConsumerState<DroidspacesBackendSettings> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final status = ref.watch(localRuntimeProvider).status;
    final stopped = !status.phase.isBusy && status.phase != RuntimePhase.running;
    final installed = status.droidspacesBackendInstalled;
    final title = installed
        ? l10n.engineBackendInstalled(status.droidspacesBackendVersion ?? '')
        : _busy
        ? l10n.engineBackendInstalling
        : l10n.engineBackendInstall;
    final subtitle = status.droidspacesBackendPendingReboot
        ? l10n.engineBackendRebootHint
        : status.droidspacesBackendError != null
        ? l10n.engineBackendInstallFailed(status.droidspacesBackendError!)
        : installed
        ? l10n.engineBackendRestart
        : l10n.engineBackendInstallHint;
    return SettingsSection(
      title: l10n.engineBackendTitle,
      children: [
        SettingsTile(
          title: title,
          subtitle: subtitle,
          onTap: !installed && stopped && !_busy ? _install : null,
        ),
      ],
    );
  }

  Future<void> _install() async {
    setState(() => _busy = true);
    try {
      final result = await ref.read(runtimeBridgeProvider).installDroidspacesBackend();
      if (!mounted) return;
      if (result == null || result.error != null) {
        showBriefNotice(context, context.l10n.engineBackendInstallFailed(result?.error ?? 'unsupported'));
      } else {
        showBriefNotice(context, context.l10n.engineBackendRebootHint);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
