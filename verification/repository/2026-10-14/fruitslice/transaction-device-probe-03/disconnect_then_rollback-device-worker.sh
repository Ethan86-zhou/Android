#!/system/bin/sh
set -eu
umask 077
TX=cache/fruitslice-transaction-probe-47e54582866d458cb987325e16eab817/disconnect_then_rollback/transaction
LIVE=cache/fruitslice-transaction-probe-47e54582866d458cb987325e16eab817/disconnect_then_rollback/live
LOCK=cache/fruitslice-transaction-probe-47e54582866d458cb987325e16eab817/disconnect_then_rollback/live.restore-lock
phase=preparing
owns_lock=0
mode=${1:-publish}
attempt=${2:-}
mkdir "$TX/running" 2>/dev/null || exit 0
state=$(cat "$TX/state")
if [ "$mode" != publish ]; then
    phase=terminal
    case "$mode:$state" in rollback:rollback_failed|rollback:rolling_back|rollback:publishing|abort:preparing) ;;
        *) rmdir "$TX/running"; exit 0 ;; esac
elif [ "$state" != prepared ]; then rmdir "$TX/running"; exit 0; fi
set_state() { printf "%s\n" "$1" > "$TX/state.next" && mv -f "$TX/state.next" "$TX/state"; }
digest() { value=$(sha256sum "$1") || return 1; printf "%s" "${value%% *}"; }
verify() { actual=$(digest "$1") && [ "$actual" = "$2" ]; }
rollback() {
    rollback_ok=1
    if [ -f "$TX/rollback/present-history.xml" ]; then
        if cp -p "$TX/rollback/history.xml" "$LIVE/history.xml.rollback-133eca6d20a06060" && verify "$LIVE/history.xml.rollback-133eca6d20a06060" "$(cat "$TX/rollback/history.xml.sha256")" && mv -f "$LIVE/history.xml.rollback-133eca6d20a06060" "$LIVE/history.xml"; then :; else rollback_ok=0; fi
    else
        rm -f "$LIVE/history.xml" || rollback_ok=0
    fi
    if [ -f "$TX/rollback/present-history.xml.bak" ]; then
        if cp -p "$TX/rollback/history.xml.bak" "$LIVE/history.xml.bak.rollback-133eca6d20a06060" && verify "$LIVE/history.xml.bak.rollback-133eca6d20a06060" "$(cat "$TX/rollback/history.xml.bak.sha256")" && mv -f "$LIVE/history.xml.bak.rollback-133eca6d20a06060" "$LIVE/history.xml.bak"; then :; else rollback_ok=0; fi
    else
        rm -f "$LIVE/history.xml.bak" || rollback_ok=0
    fi
    if [ -f "$TX/rollback/present-history.xml" ]; then
        verify "$LIVE/history.xml" "$(cat "$TX/rollback/history.xml.sha256")" || rollback_ok=0
    elif [ -e "$LIVE/history.xml" ] || [ -L "$LIVE/history.xml" ]; then rollback_ok=0; fi
    if [ -f "$TX/rollback/present-history.xml.bak" ]; then
        verify "$LIVE/history.xml.bak" "$(cat "$TX/rollback/history.xml.bak.sha256")" || rollback_ok=0
    elif [ -e "$LIVE/history.xml.bak" ] || [ -L "$LIVE/history.xml.bak" ]; then rollback_ok=0; fi
    [ "$rollback_ok" -eq 1 ]
}
finalize() {
    code=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$(cat "$TX/state" 2>/dev/null)" = committed ]; then phase=committed; fi
    if [ "$phase" = publishing ]; then
        set_state rolling_back
        if rollback; then set_state rolled_back; else set_state rollback_failed; fi
    elif [ "$phase" = preparing ]; then set_state failed_preparation; fi
    final_state=$(cat "$TX/state" 2>/dev/null)
    if [ "$owns_lock" -eq 1 ]; then
        case "$final_state" in committed|rolled_back|failed_preparation)
            rm -f "$LOCK/owner"; rmdir "$LOCK" ;; esac
    fi
    rmdir "$TX/running"
    exit "$code"
}
trap finalize EXIT
trap 'exit 130' HUP INT TERM
if [ "$mode" != publish ]; then
    [ "$(cat "$LOCK/owner")" = "$TX" ] || exit 1
    owns_lock=1
    if [ "$mode" = rollback ]; then
        [ -f "$TX/rollback.ready" ] || exit 1
        phase=publishing
        set_state rolling_back
        printf "%s" "$attempt" > "$TX/recovery.attempt"
    else phase=preparing; fi
    exit 92
fi
mkdir "$LOCK" 2>/dev/null || exit 1
owns_lock=1
printf "%s\n" "$TX" > "$LOCK/owner"
set_state preparing
[ ! -L "$LIVE" ]
mkdir -p "$LIVE" "$TX/rollback"
if [ -e "$LIVE/history.xml" ] || [ -L "$LIVE/history.xml" ]; then
    [ -f "$LIVE/history.xml" ] && [ ! -L "$LIVE/history.xml" ]
    original=$(digest "$LIVE/history.xml")
    cp -p "$LIVE/history.xml" "$TX/rollback/history.xml"
    verify "$TX/rollback/history.xml" "$original"
    printf "%s\n" "$original" > "$TX/rollback/history.xml.sha256"
    touch "$TX/rollback/present-history.xml"
fi
if [ -e "$LIVE/history.xml.bak" ] || [ -L "$LIVE/history.xml.bak" ]; then
    [ -f "$LIVE/history.xml.bak" ] && [ ! -L "$LIVE/history.xml.bak" ]
    original=$(digest "$LIVE/history.xml.bak")
    cp -p "$LIVE/history.xml.bak" "$TX/rollback/history.xml.bak"
    verify "$TX/rollback/history.xml.bak" "$original"
    printf "%s\n" "$original" > "$TX/rollback/history.xml.bak.sha256"
    touch "$TX/rollback/present-history.xml.bak"
fi
verify "$TX/staged/history.xml" 367fc019f458e2cf4d21430b005fbf72ab8869ef7764359abeb9c25a1bf27c24
verify "$TX/staged/history.xml.bak" 529dd287dbde10c5f49df58d009b61760f6d28ccc5e710622b635b35f2ef9d65
touch "$TX/rollback.ready"
set_state publishing
phase=publishing
cp -p "$TX/staged/history.xml" "$LIVE/history.xml.publish-133eca6d20a06060"
verify "$LIVE/history.xml.publish-133eca6d20a06060" 367fc019f458e2cf4d21430b005fbf72ab8869ef7764359abeb9c25a1bf27c24
mv -f "$LIVE/history.xml.publish-133eca6d20a06060" "$LIVE/history.xml"
sleep 2
if [ ! -f "$LIVE/test-fault-released" ]; then exit 91; fi # test-only injected publication failure
cp -p "$TX/staged/history.xml.bak" "$LIVE/history.xml.bak.publish-133eca6d20a06060"
verify "$LIVE/history.xml.bak.publish-133eca6d20a06060" 529dd287dbde10c5f49df58d009b61760f6d28ccc5e710622b635b35f2ef9d65
mv -f "$LIVE/history.xml.bak.publish-133eca6d20a06060" "$LIVE/history.xml.bak"
verify "$LIVE/history.xml" 367fc019f458e2cf4d21430b005fbf72ab8869ef7764359abeb9c25a1bf27c24
verify "$LIVE/history.xml.bak" 529dd287dbde10c5f49df58d009b61760f6d28ccc5e710622b635b35f2ef9d65
set_state committed
phase=committed
exit 0
