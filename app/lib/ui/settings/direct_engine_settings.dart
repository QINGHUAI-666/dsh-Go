import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import 'settings_widgets.dart';

/// Direct launch and tawcroot launch, both off by default and at most one on; a
/// change applies only after a deliberate engine stop/start.
class DirectEngineSettings extends ConsumerStatefulWidget {
  const DirectEngineSettings({super.key});

  @override
  ConsumerState<DirectEngineSettings> createState() => _DirectEngineSettingsState();
}

class _DirectEngineSettingsState extends ConsumerState<DirectEngineSettings> {
  bool _saving = false;
  bool _checking = false;
  bool _installingBackend = false;
  DroidspacesStatus? _probe;

  @override
  Widget build(BuildContext context) {
    final runtime = ref.watch(localRuntimeProvider);
    final status = runtime.status;
    final l10n = context.l10n;
    final stopped = !status.phase.isBusy && status.phase != RuntimePhase.running;
    final enabled =
        runtime.loaded && stopped && !_saving && (status.directEngineAvailable || status.directEngineEnabled);
    // Safe mode launches compatibly by design: not a sign the runtime needs it.
    // After Direct failed to launch, native reports the switch off until the
    // user turns it on again (a new try).
    final fallback = status.directEngineFallbackReason;
    final directFailed = fallback == 'directFailed';
    // A tawcroot start's fallback is the tawcroot switch's to explain.
    final runtimeNeedsCompatible =
        !status.tawcEngineEnabled &&
        fallback != null &&
        fallback != 'safeRecovery' &&
        !directFailed &&
        !fallback.startsWith('tawcroot');
    final tawcFailed = fallback == 'tawcrootFailed';
    final tawcEnabled =
        runtime.loaded && stopped && !_saving && (status.tawcEngineAvailable || status.tawcEngineEnabled);
    final tawcHint = !status.tawcEngineAvailable
        ? l10n.phoneTawcUnavailable
        : tawcFailed && !status.tawcEngineEnabled
        ? l10n.phoneTawcFailedHint
        : status.tawcEngineEnabled && fallback != null && fallback != 'safeRecovery'
        ? l10n.phoneDirectCompatibleHint
        : !stopped
        ? l10n.phoneDirectStopFirst
        : status.inSafeMode
        ? l10n.phoneDirectSafeHint
        : l10n.phoneTawcHint;
    final hint = !status.directEngineAvailable
        ? l10n.phoneDirectNeedsRuntime
        : directFailed && !status.directEngineEnabled
        ? l10n.phoneDirectFailedHint
        : runtimeNeedsCompatible
        ? l10n.phoneDirectCompatibleHint
        : !stopped
        ? l10n.phoneDirectStopFirst
        : status.inSafeMode
        ? l10n.phoneDirectSafeHint
        : l10n.phoneDirectHint;

    final probe = _probe;
    final droidspaces = probe ?? DroidspacesStatus(
      checked: status.droidspacesChecked,
      root: false,
      binary: null,
      requirements: null,
      version: status.droidspacesVersion,
      error: status.droidspacesError,
    );
    final backendSegments = [
      PillSegment('droidspaces', l10n.engineBackendDroidspaces),
      PillSegment('proot', l10n.engineBackendProot),
    ];
    return SettingsSection(
      title: l10n.phoneDeveloperOptions,
      children: [
        SettingsSegmentTile<String>(
          title: l10n.engineBackendTitle,
          subtitle: status.inSafeMode ? l10n.phoneDirectSafeHint : l10n.engineBackendHint,
          segments: backendSegments,
          selected: status.runtimeBackend,
          onChanged: stopped && !_saving ? _changeBackend : null,
        ),
        SettingsTile(
          title: _checking ? l10n.engineBackendChecking : l10n.engineBackendCheck,
          subtitle: _probeHint(l10n, droidspaces),
          onTap: _checking ? null : _check,
        ),
        if (!status.droidspacesBackendInstalled)
          SettingsTile(
            title: _installingBackend ? l10n.engineBackendInstalling : l10n.engineBackendInstall,
            subtitle: status.droidspacesBackendError == null
                ? l10n.engineBackendInstallHint
                : l10n.engineBackendInstallFailed(status.droidspacesBackendError!),
            onTap: stopped && !_installingBackend ? _installBackend : null,
          )
        else ...[
          SettingsTile(
            title: l10n.engineBackendInstalled(status.droidspacesBackendVersion ?? ''),
            subtitle: status.droidspacesBackendPendingReboot ? l10n.engineBackendRebootHint : null,
          ),
          if (!status.droidspacesBackendPendingReboot)
            SettingsTile(
              title: l10n.engineBackendUninstall,
              onTap: stopped && !_installingBackend ? _uninstallBackend : null,
            ),
        ],
        if (droidspaces.usable)
          SettingsSwitchTile(
            title: l10n.engineBackendSelinux,
            subtitle: l10n.engineBackendRestart,
            value: status.droidspacesPermissive,
            onChanged: stopped && !_saving ? _changePermissive : null,
          ),
        if (status.phase == RuntimePhase.running)
          SettingsTile(
            title: l10n.engineBackendActive(
              status.droidspacesActive ? l10n.engineBackendActiveDroidspaces : l10n.engineBackendActiveProot,
            ),
            subtitle: status.runtimeBackend == 'droidspaces' && !status.droidspacesActive
                ? l10n.engineBackendFallback
                : null,
          ),
        // Only after a direct launch failed: a fallback already runs compatibly
        // and the switch's hint says so.
        if (status.directEngineEnabled && status.launchSelectedMode == 'direct' && status.launchFailureReason != null)
          SettingsTile(
            title: l10n.phoneReturnCompatible,
            subtitle: stopped ? null : l10n.phoneDirectStopFirst,
            onTap: enabled ? () => _change(false) : null,
          ),
        SettingsSwitchTile(
          title: l10n.phoneDirectEngine,
          subtitle: hint,
          value: status.directEngineEnabled,
          onChanged: enabled ? _change : null,
        ),
        SettingsSwitchTile(
          title: l10n.phoneTawcEngine,
          subtitle: tawcHint,
          value: status.tawcEngineEnabled,
          onChanged: tawcEnabled ? _changeTawc : null,
        ),
      ],
    );
  }

  String _probeHint(L10n l10n, DroidspacesStatus probe) {
    if (!probe.checked) return l10n.engineBackendUnchecked;
    if (probe.usable) {
      final version = probe.version;
      return version == null || version.isEmpty
          ? l10n.engineBackendReady('')
          : l10n.engineBackendReady(version);
    }
    return switch (probe.error) {
      'root_denied' => l10n.engineBackendRootDenied,
      'root_timeout' => l10n.engineBackendRootTimeout,
      'droidspaces_missing' => l10n.engineBackendMissing,
      'kernel_unsupported' => l10n.engineBackendKernel,
      final code? => l10n.engineBackendCheckFailed(code),
      null => l10n.engineBackendCheckFailed('unknown'),
    };
  }

  Future<void> _check() async {
    setState(() => _checking = true);
    try {
      final status = await ref.read(runtimeBridgeProvider).checkDroidspaces();
      if (!mounted) return;
      if (status == null) {
        showBriefNotice(context, context.l10n.engineBackendCheckFailed('unsupported'));
      } else {
        setState(() => _probe = status);
      }
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  Future<void> _installBackend() async {
    setState(() => _installingBackend = true);
    try {
      final status = await ref.read(runtimeBridgeProvider).installDroidspacesBackend();
      if (!mounted) return;
      if (status == null) {
        showBriefNotice(context, context.l10n.engineBackendInstallFailed('unsupported'));
      } else if (status.error != null) {
        showBriefNotice(context, context.l10n.engineBackendInstallFailed(status.error!));
      } else {
        showBriefNotice(context, context.l10n.engineBackendRebootHint);
      }
    } finally {
      if (mounted) setState(() => _installingBackend = false);
    }
  }

  Future<void> _uninstallBackend() async {
    setState(() => _installingBackend = true);
    try {
      final status = await ref.read(runtimeBridgeProvider).uninstallDroidspacesBackend();
      if (!mounted) return;
      if (status == null || status.error != null) {
        showBriefNotice(context, context.l10n.engineBackendInstallFailed(status?.error ?? 'unsupported'));
      }
    } finally {
      if (mounted) setState(() => _installingBackend = false);
    }
  }

  Future<void> _changeBackend(String backend) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setRuntimeBackend(backend);
      if (mounted) setState(() => _probe = null);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _changePermissive(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setDroidspacesPermissive(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _changeTawc(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setTawcEngine(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _change(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setDirectEngine(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}
