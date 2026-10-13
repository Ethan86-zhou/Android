#!/system/bin/sh
set -eu
umask 077
TX=cache/lexi-transaction-probe-395650019b6c41b99b6c8108c459c3d0/disconnect-0/transaction
LIVE=cache/lexi-transaction-probe-395650019b6c41b99b6c8108c459c3d0/disconnect-0/live
LOCK=cache/lexi-transaction-probe-395650019b6c41b99b6c8108c459c3d0/disconnect-0/live.restore-lock
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
    if [ -f "$TX/rollback/present-book.db" ]; then
        if cp -p "$TX/rollback/book.db" "$LIVE/book.db.rollback-a52071ca3caf09c1" && verify "$LIVE/book.db.rollback-a52071ca3caf09c1" "$(cat "$TX/rollback/book.db.sha256")" && mv -f "$LIVE/book.db.rollback-a52071ca3caf09c1" "$LIVE/book.db"; then :; else rollback_ok=0; fi
    else
        rm -f "$LIVE/book.db" || rollback_ok=0
    fi
    if [ -f "$TX/rollback/present-book.db-journal" ]; then
        if cp -p "$TX/rollback/book.db-journal" "$LIVE/book.db-journal.rollback-a52071ca3caf09c1" && verify "$LIVE/book.db-journal.rollback-a52071ca3caf09c1" "$(cat "$TX/rollback/book.db-journal.sha256")" && mv -f "$LIVE/book.db-journal.rollback-a52071ca3caf09c1" "$LIVE/book.db-journal"; then :; else rollback_ok=0; fi
    else
        rm -f "$LIVE/book.db-journal" || rollback_ok=0
    fi
    if [ -f "$TX/rollback/present-book.db-shm" ]; then
        if cp -p "$TX/rollback/book.db-shm" "$LIVE/book.db-shm.rollback-a52071ca3caf09c1" && verify "$LIVE/book.db-shm.rollback-a52071ca3caf09c1" "$(cat "$TX/rollback/book.db-shm.sha256")" && mv -f "$LIVE/book.db-shm.rollback-a52071ca3caf09c1" "$LIVE/book.db-shm"; then :; else rollback_ok=0; fi
    else
        rm -f "$LIVE/book.db-shm" || rollback_ok=0
    fi
    if [ -f "$TX/rollback/present-book.db-wal" ]; then
        if cp -p "$TX/rollback/book.db-wal" "$LIVE/book.db-wal.rollback-a52071ca3caf09c1" && verify "$LIVE/book.db-wal.rollback-a52071ca3caf09c1" "$(cat "$TX/rollback/book.db-wal.sha256")" && mv -f "$LIVE/book.db-wal.rollback-a52071ca3caf09c1" "$LIVE/book.db-wal"; then :; else rollback_ok=0; fi
    else
        rm -f "$LIVE/book.db-wal" || rollback_ok=0
    fi
    if [ -f "$TX/rollback/present-book.db" ]; then
        verify "$LIVE/book.db" "$(cat "$TX/rollback/book.db.sha256")" || rollback_ok=0
    elif [ -e "$LIVE/book.db" ] || [ -L "$LIVE/book.db" ]; then rollback_ok=0; fi
    if [ -f "$TX/rollback/present-book.db-journal" ]; then
        verify "$LIVE/book.db-journal" "$(cat "$TX/rollback/book.db-journal.sha256")" || rollback_ok=0
    elif [ -e "$LIVE/book.db-journal" ] || [ -L "$LIVE/book.db-journal" ]; then rollback_ok=0; fi
    if [ -f "$TX/rollback/present-book.db-shm" ]; then
        verify "$LIVE/book.db-shm" "$(cat "$TX/rollback/book.db-shm.sha256")" || rollback_ok=0
    elif [ -e "$LIVE/book.db-shm" ] || [ -L "$LIVE/book.db-shm" ]; then rollback_ok=0; fi
    if [ -f "$TX/rollback/present-book.db-wal" ]; then
        verify "$LIVE/book.db-wal" "$(cat "$TX/rollback/book.db-wal.sha256")" || rollback_ok=0
    elif [ -e "$LIVE/book.db-wal" ] || [ -L "$LIVE/book.db-wal" ]; then rollback_ok=0; fi
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
if [ -e "$LIVE/book.db" ] || [ -L "$LIVE/book.db" ]; then
    [ -f "$LIVE/book.db" ] && [ ! -L "$LIVE/book.db" ]
    original=$(digest "$LIVE/book.db")
    cp -p "$LIVE/book.db" "$TX/rollback/book.db"
    verify "$TX/rollback/book.db" "$original"
    printf "%s\n" "$original" > "$TX/rollback/book.db.sha256"
    touch "$TX/rollback/present-book.db"
fi
if [ -e "$LIVE/book.db-journal" ] || [ -L "$LIVE/book.db-journal" ]; then
    [ -f "$LIVE/book.db-journal" ] && [ ! -L "$LIVE/book.db-journal" ]
    original=$(digest "$LIVE/book.db-journal")
    cp -p "$LIVE/book.db-journal" "$TX/rollback/book.db-journal"
    verify "$TX/rollback/book.db-journal" "$original"
    printf "%s\n" "$original" > "$TX/rollback/book.db-journal.sha256"
    touch "$TX/rollback/present-book.db-journal"
fi
if [ -e "$LIVE/book.db-shm" ] || [ -L "$LIVE/book.db-shm" ]; then
    [ -f "$LIVE/book.db-shm" ] && [ ! -L "$LIVE/book.db-shm" ]
    original=$(digest "$LIVE/book.db-shm")
    cp -p "$LIVE/book.db-shm" "$TX/rollback/book.db-shm"
    verify "$TX/rollback/book.db-shm" "$original"
    printf "%s\n" "$original" > "$TX/rollback/book.db-shm.sha256"
    touch "$TX/rollback/present-book.db-shm"
fi
if [ -e "$LIVE/book.db-wal" ] || [ -L "$LIVE/book.db-wal" ]; then
    [ -f "$LIVE/book.db-wal" ] && [ ! -L "$LIVE/book.db-wal" ]
    original=$(digest "$LIVE/book.db-wal")
    cp -p "$LIVE/book.db-wal" "$TX/rollback/book.db-wal"
    verify "$TX/rollback/book.db-wal" "$original"
    printf "%s\n" "$original" > "$TX/rollback/book.db-wal.sha256"
    touch "$TX/rollback/present-book.db-wal"
fi
verify "$TX/staged/book.db" 59f410ae5e17962412e2aed4f815918f634932f2abf084f00bb638c4db017850
verify "$TX/staged/book.db-journal" e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
verify "$TX/staged/book.db-shm" bfe358b8ae7f9efe64f0552d20a22869e293a07ff4aaaa3123a05aa3af9487ad
touch "$TX/rollback.ready"
set_state publishing
phase=publishing
cp -p "$TX/staged/book.db" "$LIVE/book.db.publish-a52071ca3caf09c1"
verify "$LIVE/book.db.publish-a52071ca3caf09c1" 59f410ae5e17962412e2aed4f815918f634932f2abf084f00bb638c4db017850
mv -f "$LIVE/book.db.publish-a52071ca3caf09c1" "$LIVE/book.db"
sleep 3
cp -p "$TX/staged/book.db-journal" "$LIVE/book.db-journal.publish-a52071ca3caf09c1"
verify "$LIVE/book.db-journal.publish-a52071ca3caf09c1" e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
mv -f "$LIVE/book.db-journal.publish-a52071ca3caf09c1" "$LIVE/book.db-journal"
cp -p "$TX/staged/book.db-shm" "$LIVE/book.db-shm.publish-a52071ca3caf09c1"
verify "$LIVE/book.db-shm.publish-a52071ca3caf09c1" bfe358b8ae7f9efe64f0552d20a22869e293a07ff4aaaa3123a05aa3af9487ad
mv -f "$LIVE/book.db-shm.publish-a52071ca3caf09c1" "$LIVE/book.db-shm"
rm -f "$LIVE/book.db-wal"
verify "$LIVE/book.db" 59f410ae5e17962412e2aed4f815918f634932f2abf084f00bb638c4db017850
verify "$LIVE/book.db-journal" e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
verify "$LIVE/book.db-shm" bfe358b8ae7f9efe64f0552d20a22869e293a07ff4aaaa3123a05aa3af9487ad
[ ! -e "$LIVE/book.db-wal" ] && [ ! -L "$LIVE/book.db-wal" ]
set_state committed
phase=committed
exit 0
