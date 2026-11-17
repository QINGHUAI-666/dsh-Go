import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('a status without backend fields stays on the compatible default', () {
    final status = RuntimeStatus.fromMap(const {'phase': 'installed'});
    expect(status.runtimeBackend, 'proot');
    expect(status.droidspacesChecked, isFalse);
    expect(status.droidspacesAvailable, isFalse);
    expect(status.droidspacesActive, isFalse);
  });

  test('the native Droidspaces fields are parsed independently', () {
    final status = RuntimeStatus.fromMap(const {
      'phase': 'running',
      'runtimeBackend': 'droidspaces',
      'droidspacesChecked': true,
      'droidspacesAvailable': true,
      'droidspacesActive': true,
      'droidspacesVersion': 'Droidspaces v6.3.0',
      'droidspacesError': null,
      'droidspacesPermissive': true,
    });
    expect(status.runtimeBackend, 'droidspaces');
    expect(status.droidspacesChecked, isTrue);
    expect(status.droidspacesAvailable, isTrue);
    expect(status.droidspacesActive, isTrue);
    expect(status.droidspacesVersion, 'Droidspaces v6.3.0');
    expect(status.droidspacesPermissive, isTrue);
  });

  test('a probe is usable only when root, the binary and the kernel check all pass', () {
    DroidspacesStatus probe({
      bool checked = true,
      bool root = true,
      String? binary = '/data/local/Droidspaces/bin/droidspaces',
      bool? requirements = true,
      String? error,
    }) => DroidspacesStatus.fromMap({
      'checked': checked,
      'root': root,
      'binary': binary,
      'requirements': requirements,
      'version': 'v',
      'error': error,
    });
    expect(probe().usable, isTrue);
    expect(probe(root: false).usable, isFalse);
    expect(probe(binary: null).usable, isFalse);
    expect(probe(requirements: false).usable, isFalse);
    expect(probe(requirements: null).usable, isFalse);
    expect(probe(checked: false).usable, isFalse);
  });
}
