"""Device-local, recoverable restoration of a stopped Android app's file set.

The caller persists transaction_dir in its private host backup BEFORE this call.
On a lost ADB connection it must reuse that directory, never discard the device
journal or start a second restoration against the same files blindly.
"""
import base64
import hashlib
import json
import re
import shlex
import time
from uuid import uuid4


class TransactionError(RuntimeError):
    def __init__(self, state, transaction_dir):
        self.state = state
        self.transaction_dir = transaction_dir
        super().__init__(f"Device restore transaction {state}; retained at {transaction_dir}")


def _path(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)*", value):
        raise ValueError("Transaction paths must be safe app-relative paths")
    if any(part in (".", "..") for part in value.split("/")):
        raise ValueError("Relative traversal is not permitted")
    return value


def _name(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*", value):
        raise ValueError("Managed files must have safe leaf names")
    return value


def _shell(command, package, script):
    return command("shell", "-T", "run-as", package, "sh", "-c", shlex.quote(script))


def _digest(command, package, path):
    parts = command("shell", "-T", "run-as", package, "sha256sum", path).decode("ascii").split()
    if len(parts) != 2 or parts[1] != path or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
        raise RuntimeError("Invalid device checksum response")
    return parts[0]


def read_verified(command, package, path):
    before = _digest(command, package, path)
    data = command("exec-out", "run-as", package, "cat", path)
    if hashlib.sha256(data).hexdigest() != before or _digest(command, package, path) != before:
        raise RuntimeError("Binary download does not match device checksum")
    return data


def stage_verified(command, package, path, data):
    encoded = path + ".b64"
    try:
        command("shell", "-T", "run-as", package, "dd", "of=" + encoded,
                data=base64.b64encode(data))
        _shell(command, package, "base64 -d " + shlex.quote(encoded) + " > " + shlex.quote(path))
    finally:
        command("shell", "-T", "run-as", package, "rm", "-f", encoded)
    if read_verified(command, package, path) != data:
        raise RuntimeError("Binary upload does not match host bytes")


def _state(command, package, tx):
    value = _shell(command, package, f"if [ -f {tx}/state ]; then cat {tx}/state; else printf absent; fi")
    return value.decode("ascii").strip()


def worker_script(live_dir, tx, hashes, managed_names, *, fail_after_publish=0, pause_after_publish=0):
    """Generate the complete device-local operation, including local rollback."""
    live_dir = _path(live_dir); tx = _path(tx)
    names = sorted(_name(name) for name in managed_names)
    for name, digest in hashes.items():
        if name not in names or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("Invalid staged file identity")
    if fail_after_publish < 0 or pause_after_publish < 0 or pause_after_publish > 30:
        raise ValueError("Invalid test fault settings")
    token = hashlib.sha256(tx.encode()).hexdigest()[:16]
    lines = ["#!/system/bin/sh", "set -eu", "umask 077",
             f"TX={shlex.quote(tx)}", f"LIVE={shlex.quote(live_dir)}",
             f"LOCK={shlex.quote(live_dir + '.restore-lock')}", "phase=preparing", "owns_lock=0",
             'mode=${1:-publish}', 'attempt=${2:-}',
             'mkdir "$TX/running" 2>/dev/null || exit 0',
             'state=$(cat "$TX/state")',
             'if [ "$mode" != publish ]; then',
             '    phase=terminal',
             '    case "$mode:$state" in rollback:rollback_failed|rollback:rolling_back|rollback:publishing|abort:preparing) ;;',
             '        *) rmdir "$TX/running"; exit 0 ;; esac',
             'elif [ "$state" != prepared ]; then rmdir "$TX/running"; exit 0; fi',
             'set_state() { printf "%s\\n" "$1" > "$TX/state.next" && mv -f "$TX/state.next" "$TX/state"; }',
             'digest() { value=$(sha256sum "$1") || return 1; printf "%s" "${value%% *}"; }',
             'verify() { actual=$(digest "$1") && [ "$actual" = "$2" ]; }',
             'rollback() {', '    rollback_ok=1']
    for name in names:
        restore_tmp = f'$LIVE/{name}.rollback-{token}'
        lines += [f'    if [ -f "$TX/rollback/present-{name}" ]; then',
                  f'        if cp -p "$TX/rollback/{name}" "{restore_tmp}" && '
                  f'verify "{restore_tmp}" "$(cat "$TX/rollback/{name}.sha256")" && '
                  f'mv -f "{restore_tmp}" "$LIVE/{name}"; then :; else rollback_ok=0; fi',
                  '    else', f'        rm -f "$LIVE/{name}" || rollback_ok=0', '    fi']
    for name in names:
        lines += [f'    if [ -f "$TX/rollback/present-{name}" ]; then',
                  f'        verify "$LIVE/{name}" "$(cat "$TX/rollback/{name}.sha256")" || rollback_ok=0',
                  f'    elif [ -e "$LIVE/{name}" ] || [ -L "$LIVE/{name}" ]; then rollback_ok=0; fi']
    lines += ['    [ "$rollback_ok" -eq 1 ]', '}',
              'finalize() {', '    code=$?', '    trap - EXIT HUP INT TERM', '    set +e',
              '    if [ "$(cat "$TX/state" 2>/dev/null)" = committed ]; then phase=committed; fi',
              '    if [ "$phase" = publishing ]; then',
              '        set_state rolling_back',
              '        if rollback; then set_state rolled_back; else set_state rollback_failed; fi',
              '    elif [ "$phase" = preparing ]; then set_state failed_preparation; fi',
              '    final_state=$(cat "$TX/state" 2>/dev/null)',
              '    if [ "$owns_lock" -eq 1 ]; then',
              '        case "$final_state" in committed|rolled_back|failed_preparation)',
              '            rm -f "$LOCK/owner"; rmdir "$LOCK" ;; esac', '    fi',
              '    rmdir "$TX/running"', '    exit "$code"', '}',
              "trap finalize EXIT", "trap 'exit 130' HUP INT TERM",
              'if [ "$mode" != publish ]; then',
              '    [ "$(cat "$LOCK/owner")" = "$TX" ] || exit 1',
              '    owns_lock=1',
              '    if [ "$mode" = rollback ]; then',
              '        [ -f "$TX/rollback.ready" ] || exit 1',
              '        phase=publishing', '        set_state rolling_back',
              '        printf "%s" "$attempt" > "$TX/recovery.attempt"',
              '    else phase=preparing; fi', '    exit 92', 'fi',
              'mkdir "$LOCK" 2>/dev/null || exit 1', 'owns_lock=1',
              'printf "%s\\n" "$TX" > "$LOCK/owner"',
              'set_state preparing', '[ ! -L "$LIVE" ]', 'mkdir -p "$LIVE" "$TX/rollback"']
    for name in names:
        lines += [f'if [ -e "$LIVE/{name}" ] || [ -L "$LIVE/{name}" ]; then',
                  f'    [ -f "$LIVE/{name}" ] && [ ! -L "$LIVE/{name}" ]',
                  f'    original=$(digest "$LIVE/{name}")',
                  f'    cp -p "$LIVE/{name}" "$TX/rollback/{name}"',
                  f'    verify "$TX/rollback/{name}" "$original"',
                  f'    printf "%s\\n" "$original" > "$TX/rollback/{name}.sha256"',
                  f'    touch "$TX/rollback/present-{name}"', 'fi']
    for name, digest in sorted(hashes.items()):
        lines.append(f'verify "$TX/staged/{name}" {digest}')
    # No live mutation is allowed until the entire rollback set is ready.
    lines += ['touch "$TX/rollback.ready"', 'set_state publishing', 'phase=publishing']
    for index, name in enumerate(names, 1):
        if name in hashes:
            temporary = f'$LIVE/{name}.publish-{token}'
            lines += [f'cp -p "$TX/staged/{name}" "{temporary}"',
                      f'verify "{temporary}" {hashes[name]}',
                      f'mv -f "{temporary}" "$LIVE/{name}"']
        else:
            lines.append(f'rm -f "$LIVE/{name}"')
        if index == 1 and pause_after_publish:
            lines.append(f"sleep {float(pause_after_publish):g}")
        if index == fail_after_publish:
            lines.append('if [ ! -f "$LIVE/test-fault-released" ]; then exit 91; fi # test-only injected publication failure')
    for name in names:
        lines.append(f'verify "$LIVE/{name}" {hashes[name]}' if name in hashes
                     else f'[ ! -e "$LIVE/{name}" ] && [ ! -L "$LIVE/{name}" ]')
    lines += ['set_state committed', 'phase=committed', 'exit 0', '']
    return "\n".join(lines).encode("utf-8")


def _verify_rollback(command, package, live_dir, tx, names):
    for name in names:
        script = (f'if [ -f {tx}/rollback/present-{name} ]; then '
                  f'expected=$(cat {tx}/rollback/{name}.sha256) && '
                  f'actual=$(sha256sum {live_dir}/{name}) && [ "${{actual%% *}}" = "$expected" ]; '
                  f'else [ ! -e {live_dir}/{name} ] && [ ! -L {live_dir}/{name} ]; fi')
        try:
            _shell(command, package, script)
        except RuntimeError as error:
            raise TransactionError("rollback_state_changed", tx) from error


def _recovery_mode(command, package, live_dir, tx, state):
    if state not in {"preparing", "publishing", "rolling_back", "rollback_failed"}:
        return ""
    # running is removed only when this transaction's worker exits. The global
    # owner and complete local rollback marker must also match before recovery.
    script = (f'if [ ! -d {tx}/running ] && [ -f {live_dir}.restore-lock/owner ] && '
              f'[ "$(cat {live_dir}.restore-lock/owner)" = {tx} ]; then '
              f'if [ {state} = preparing ]; then printf abort; '
              f'elif [ -f {tx}/rollback.ready ]; then printf rollback; fi; fi')
    return _shell(command, package, script).decode("ascii").strip()


def restore_transaction(command, package, live_dir, files, managed_names, transaction_dir,
                        *, timeout=60, _fail_after_publish=0, _pause_after_publish=0):
    """Restore a set, or reconnect to the SAME persisted transaction after ADB loss.

    No staging or rollback copy is deleted here, on success or failure. A later
    explicit call may retry a fully rolled-back transaction in a retained child
    journal; callers must not clear pendingTransaction on a failed call.
    Test injection options are reserved for independent generated cache fixtures.
    """
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]+", package):
        raise ValueError("Invalid package")
    live_dir = _path(live_dir); tx = _path(transaction_dir)
    names = sorted(_name(name) for name in managed_names)
    if not set(files) <= set(names) or not names or live_dir == tx or tx.startswith(live_dir + "/"):
        raise ValueError("Invalid transaction file set or directory")
    if (_fail_after_publish or _pause_after_publish) and not live_dir.startswith("cache/"):
        raise ValueError("Fault injection is restricted to generated cache fixtures")
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}
    script = worker_script(live_dir, tx, hashes, names,
                           fail_after_publish=_fail_after_publish, pause_after_publish=_pause_after_publish)
    plan = json.dumps({"format": 1, "package": package, "liveDir": live_dir,
                       "managedNames": names, "files": hashes, "workerSha256": hashlib.sha256(script).hexdigest()},
                      sort_keys=True, separators=(",", ":")).encode()
    state = _state(command, package, tx)
    recovery_mode = _recovery_mode(command, package, live_dir, tx, state)
    if state in {"rolled_back", "failed_preparation"}:
        # A retry gets its own copies. Never overwrite the preceding transaction's
        # only rollback set, and resume an existing child before checking old live
        # hashes (the child may already be publishing a newer complete set).
        if read_verified(command, package, tx + "/plan.json") != plan:
            raise TransactionError("plan_mismatch", tx)
        child = tx + "/retry"
        if _state(command, package, child) == "absent" and state == "rolled_back":
            _verify_rollback(command, package, live_dir, tx, names)
        return restore_transaction(command, package, live_dir, files, names, child,
                                   timeout=timeout, _fail_after_publish=_fail_after_publish,
                                   _pause_after_publish=_pause_after_publish)
    if state == "absent":
        command("shell", "-T", "run-as", package, "mkdir", "-p", tx + "/staged")
        stage_verified(command, package, tx + "/plan.json", plan)
        _shell(command, package, f"printf staging > {tx}/state.next && mv {tx}/state.next {tx}/state")
        state = "staging"
    elif read_verified(command, package, tx + "/plan.json") != plan:
        raise TransactionError("plan_mismatch", tx)
    if state == "staging":
        for name, data in sorted(files.items()):
            stage_verified(command, package, tx + "/staged/" + name, data)
        stage_verified(command, package, tx + "/worker.sh", script)
        _shell(command, package, f"printf prepared > {tx}/state.next && mv {tx}/state.next {tx}/state")
        state = "prepared"
    recovery_token = uuid4().hex if recovery_mode else ""
    if state == "prepared" or recovery_mode:
        # Child has its own session, closed stdin and redirected output. ADB only
        # launches it; the publication/rollback operation does not depend on ADB.
        mode = " " + recovery_mode + " " + recovery_token if recovery_mode else ""
        launch = f"nohup setsid sh {tx}/worker.sh{mode} >> {tx}/worker.log 2>&1 < /dev/null &"
        _shell(command, package, launch)
    deadline = time.monotonic() + timeout
    while True:
        state = _state(command, package, tx)
        if state == "committed":
            for name, data in files.items():
                if read_verified(command, package, live_dir + "/" + name) != data:
                    raise TransactionError("verification_failed", tx)
            for name in set(names) - set(files):
                _shell(command, package, f"[ ! -e {live_dir}/{name} ] && [ ! -L {live_dir}/{name} ]")
            return {"state": state, "transactionDir": tx, "files": hashes}
        if state in {"rolled_back", "rollback_failed", "failed_preparation"}:
            if recovery_mode and state in {"rolled_back", "failed_preparation"}:
                # This explicit reconnect repaired a retained failed transaction.
                # Retry the requested target in a new child without losing copies.
                return restore_transaction(command, package, live_dir, files, names, tx,
                        timeout=timeout, _fail_after_publish=_fail_after_publish,
                        _pause_after_publish=_pause_after_publish)
            if recovery_mode and state == "rollback_failed":
                observed = _shell(command, package,
                    f"if [ -f {tx}/recovery.attempt ]; then cat {tx}/recovery.attempt; fi").decode("ascii").strip()
                if observed != recovery_token and time.monotonic() < deadline:
                    time.sleep(0.1)
                    continue
                # The attempt marker is written after leaving rollback_failed.
                # Reread state to avoid mixing observations across that transition.
                if observed == recovery_token and _state(command, package, tx) != "rollback_failed":
                    continue
            raise TransactionError(state, tx)
        if state not in {"prepared", "preparing", "publishing", "rolling_back"}:
            raise TransactionError("unknown_state_" + state, tx)
        if time.monotonic() >= deadline:
            raise TransactionError("pending", tx)
        time.sleep(0.1)
